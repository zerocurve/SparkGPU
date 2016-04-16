/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.execution

import org.apache.spark.{broadcast, TaskContext}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.expressions.codegen._
import org.apache.spark.sql.catalyst.plans.physical.Partitioning
import org.apache.spark.sql.catalyst.rules.Rule
import org.apache.spark.sql.catalyst.util.toCommentSafeString
import org.apache.spark.sql.execution.aggregate.TungstenAggregate
import org.apache.spark.sql.execution.columnar.InMemoryColumnarTableScan
import org.apache.spark.sql.execution.joins.{BroadcastHashJoinExec, SortMergeJoinExec}
import org.apache.spark.sql.execution.metric.{LongSQLMetricValue, SQLMetrics}
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.types._

/**
 * An interface for those physical operators that support codegen.
 */
trait CodegenSupport extends SparkPlan {

  /** Prefix used in the current operator's variable names. */
  private def variablePrefix: String = this match {
    case _: TungstenAggregate => "agg"
    case _: BroadcastHashJoinExec => "bhj"
    case _: SortMergeJoinExec => "smj"
    case _: RDDScanExec => "rdd"
    case _: DataSourceScanExec => "scan"
    case _ => nodeName.toLowerCase
  }

  /**
   * Creates a metric using the specified name.
   *
   * @return name of the variable representing the metric
   */
  def metricTerm(ctx: CodegenContext, name: String): String = {
    val metric = ctx.addReferenceObj(name, longMetric(name))
    val value = ctx.freshName("metricValue")
    val cls = classOf[LongSQLMetricValue].getName
    ctx.addMutableState(cls, value, s"$value = ($cls) $metric.localValue();")
    value
  }

  /**
   * Whether this SparkPlan support whole stage codegen or not.
   */
  def supportCodegen: Boolean = true

  /**
   * Which SparkPlan is calling produce() of this one. It's itself for the first SparkPlan.
   */
  protected var parent: CodegenSupport = null

  /**
   * Whether this SparkPlan prefers to accept UnsafeRow as input in doConsume
   */
  def preferUnsafeRow: Boolean = false

  /**
   * Returns all the RDDs of InternalRow which generates the input rows.
   *
   * Note: right now we support up to two RDDs.
   */
  def inputRDDs(): Seq[RDD[InternalRow]]

  /**
   * Returns Java source code to process the rows from input RDD.
   */
  final def produce(ctx: CodegenContext, parent: CodegenSupport): String = executeQuery {
    this.parent = parent
    ctx.freshNamePrefix = variablePrefix
    s"""
       |/*** PRODUCE: ${toCommentSafeString(this.simpleString)} */
       |${doProduce(ctx)}
     """.stripMargin
  }

  /**
   * Generate the Java source code to process, should be overridden by subclass to support codegen.
   *
   * doProduce() usually generate the framework, for example, aggregation could generate this:
   *
   *   if (!initialized) {
   *     # create a hash map, then build the aggregation hash map
   *     # call child.produce()
   *     initialized = true;
   *   }
   *   while (hashmap.hasNext()) {
   *     row = hashmap.next();
   *     # build the aggregation results
   *     # create variables for results
   *     # call consume(), which will call parent.doConsume()
   *      if (shouldStop()) return;
   *   }
   */
  protected def doProduce(ctx: CodegenContext): String

  /**
   * Consume the generated columns or row from current SparkPlan, call it's parent's doConsume().
   */
  final def consume(ctx: CodegenContext, outputVars: Seq[ExprCode], row: String = null): String = {
    val inputVars =
      if (row != null) {
        ctx.currentVars = null
        ctx.INPUT_ROW = row
        output.zipWithIndex.map { case (attr, i) =>
          BoundReference(i, attr.dataType, attr.nullable).genCode(ctx)
        }
      } else {
        assert(outputVars != null)
        assert(outputVars.length == output.length)
        // outputVars will be used to generate the code for UnsafeRow, so we should copy them
        outputVars.map(_.copy())
      }

    val rowVar = if (row != null) {
      ExprCode("", "false", row)
    } else {
      if (outputVars.nonEmpty) {
        val colExprs = output.zipWithIndex.map { case (attr, i) =>
          BoundReference(i, attr.dataType, attr.nullable)
        }
        val evaluateInputs = evaluateVariables(outputVars)
        // generate the code to create a UnsafeRow
        ctx.currentVars = outputVars
        val ev = GenerateUnsafeProjection.createCode(ctx, colExprs, false)
        val code = s"""
          |$evaluateInputs
          |${ev.code.trim}
         """.stripMargin.trim
        ExprCode(code, "false", ev.value)
      } else {
        // There is no columns
        ExprCode("", "false", "unsafeRow")
      }
    }

    ctx.freshNamePrefix = parent.variablePrefix
    val evaluated = evaluateRequiredVariables(output, inputVars, parent.usedInputs)
    s"""
       |
       |/*** CONSUME: ${toCommentSafeString(parent.simpleString)} */
       |$evaluated
       |${parent.doConsume(ctx, inputVars, rowVar)}
     """.stripMargin
  }

  /**
   * Returns source code to evaluate all the variables, and clear the code of them, to prevent
   * them to be evaluated twice.
   */
  protected def evaluateVariables(variables: Seq[ExprCode]): String = {
    val evaluate = variables.filter(_.code != "").map(_.code.trim).mkString("\n")
    variables.foreach(_.code = "")
    evaluate
  }

  /**
   * Returns source code to evaluate the variables for required attributes, and clear the code
   * of evaluated variables, to prevent them to be evaluated twice.
   */
  protected def evaluateRequiredVariables(
      attributes: Seq[Attribute],
      variables: Seq[ExprCode],
      required: AttributeSet): String = {
    val evaluateVars = new StringBuilder
    variables.zipWithIndex.foreach { case (ev, i) =>
      if (ev.code != "" && required.contains(attributes(i))) {
        evaluateVars.append(ev.code.trim + "\n")
        ev.code = ""
      }
    }
    evaluateVars.toString()
  }

  /**
   * The subset of inputSet those should be evaluated before this plan.
   *
   * We will use this to insert some code to access those columns that are actually used by current
   * plan before calling doConsume().
   */
  def usedInputs: AttributeSet = references

  /**
   * Generate the Java source code to process the rows from child SparkPlan.
   *
   * This should be override by subclass to support codegen.
   *
   * For example, Filter will generate the code like this:
   *
   *   # code to evaluate the predicate expression, result is isNull1 and value2
   *   if (isNull1 || !value2) continue;
   *   # call consume(), which will call parent.doConsume()
   *
   * Note: A plan can either consume the rows as UnsafeRow (row), or a list of variables (input).
   */
  def doConsume(ctx: CodegenContext, input: Seq[ExprCode], row: ExprCode): String = {
    throw new UnsupportedOperationException
  }
}


/**
 * InputAdapter is used to hide a SparkPlan from a subtree that support codegen.
 *
 * This is the leaf node of a tree with WholeStageCodegen, is used to generate code that consumes
 * an RDD iterator of InternalRow.
 */
case class InputAdapter(child: SparkPlan) extends UnaryExecNode with CodegenSupport {

  override def output: Seq[Attribute] = child.output
  override def outputPartitioning: Partitioning = child.outputPartitioning
  override def outputOrdering: Seq[SortOrder] = child.outputOrdering

  override def doExecute(): RDD[InternalRow] = {
    child.execute()
  }

  override def doExecuteBroadcast[T](): broadcast.Broadcast[T] = {
    child.doExecuteBroadcast()
  }

  override def inputRDDs(): Seq[RDD[InternalRow]] = {
    child.execute() :: Nil
  }

  override def doProduce(ctx: CodegenContext): String = {
    ctx.enableColumnCodeGen = true
    val input = ctx.freshName("input")
    ctx.inputHolder = input
    // Right now, InputAdapter is only used when there is one input RDD.
    ctx.addMutableState("scala.collection.Iterator", input, s"$input = inputs[0];")

    if (ctx.isRow) {
      val row = ctx.freshName("row")
    s"""
       | while ($input.hasNext()) {
       |   InternalRow $row = (InternalRow) $input.next();
       |   ${consume(ctx, null, row).trim}
       |   if (shouldStop()) return;
       | }
     """.stripMargin
    } else {
      val columnarBatchClz = "org.apache.spark.sql.execution.columnar.CachedBatch"
      val columnarItrClz = "org.apache.spark.sql.execution.columnar.ColumnarIterator"
      val columnVectorClz = "org.apache.spark.sql.execution.vectorized.ColumnVector"
      val batch = ctx.columnarBatchName
      val idx = ctx.columnarBatchIdxName
      val rowidx = ctx.freshName("rowIdx")
      val col = ctx.freshName("col")
      val numrows = ctx.freshName("numRows")
      val colVars = output.indices.map(i => ctx.freshName("col" + i))
      val columnAssigns = colVars.zipWithIndex.map { case (name, i) =>
        ctx.addMutableState(columnVectorClz, name, s"$name = null;", s"$name = null;")
        s"$name = batch.column((($columnarItrClz)$input).getColumnIndexes()[$i]);" }

      ctx.currentVars = null
      val columns = (output zip colVars).map { case (attr, colVar) =>
        new ColumnVectorReference(colVar, rowidx, attr.dataType, attr.nullable).gen(ctx) }
    s"""
       | while (true) {
       |   if (${idx} == 0) {
       |     if (${ctx.columnarItrName}.hasNext()) {
       |       ${batch} = ${ctx.columnarItrName}.next();
       |     } else {
       |       cleanup();
       |       break;
       |     }
       |   }
       |
       |   $columnarBatchClz batch = ($columnarBatchClz) $batch;
       |   if ($idx == 0) {
       |     ${columnAssigns.mkString("", "\n", "")}
       |   }
       |
       |   int $numrows = batch.numRows();
       |   while ($idx < $numrows) {
       |     int $rowidx = $idx++;
       |     ${consume(ctx, columns, null).trim}
       |     if (shouldStop()) return;
       |   }
       |   $idx = 0;
       | }
     """.stripMargin
    }
  }

  override def simpleString: String = "INPUT"

  override def treeChildren: Seq[SparkPlan] = Nil
}

object WholeStageCodegenExec {
  val PIPELINE_DURATION_METRIC = "duration"
}

/**
 * WholeStageCodegen compile a subtree of plans that support codegen together into single Java
 * function.
 *
 * Here is the call graph of to generate Java source (plan A support codegen, but plan B does not):
 *
 *   WholeStageCodegen       Plan A               FakeInput        Plan B
 * =========================================================================
 *
 * -> execute()
 *     |
 *  doExecute() --------->   inputRDDs() -------> inputRDDs() ------> execute()
 *     |
 *     +----------------->   produce()
 *                             |
 *                          doProduce()  -------> produce()
 *                                                   |
 *                                                doProduce()
 *                                                   |
 *                         doConsume() <--------- consume()
 *                             |
 *  doConsume()  <--------  consume()
 *
 * SparkPlan A should override doProduce() and doConsume().
 *
 * doCodeGen() will create a CodeGenContext, which will hold a list of variables for input,
 * used to generated code for BoundReference.
 */
case class WholeStageCodegenExec(child: SparkPlan) extends UnaryExecNode with CodegenSupport {

  override def output: Seq[Attribute] = child.output
  override def outputPartitioning: Partitioning = child.outputPartitioning
  override def outputOrdering: Seq[SortOrder] = child.outputOrdering

  override private[sql] lazy val metrics = Map(
    "pipelineTime" -> SQLMetrics.createTimingMetric(sparkContext,
      WholeStageCodegenExec.PIPELINE_DURATION_METRIC))

  /**
   * Generates code for this subtree.
   *
   * @return the tuple of the codegen context and the actual generated source.
   */
  def doCodeGen(): (CodegenContext, String) = {
    val ctx = new CodegenContext
    ctx.isRow = true
    val codeRow = child.asInstanceOf[CodegenSupport].produce(ctx, this)
    val referUnsafeRow = child.find(c => c.isInstanceOf[CodegenSupport] &&
      c.asInstanceOf[CodegenSupport].preferUnsafeRow) match {
        case Some(c) => true
        case None => false
      }
    val useInMemoryColumnar = child.find(c => c.isInstanceOf[InMemoryColumnarTableScan]) match {
      case Some(c) => true
      case None => false
    }
    val enableColumnCodeGen = ctx.enableColumnCodeGen && !referUnsafeRow && useInMemoryColumnar &&
      sqlContext.getConf(SQLConf.COLUMN_VECTOR_CODEGEN.key).toBoolean
    val codeCol = if (enableColumnCodeGen) {
      ctx.isRow = false
      child.asInstanceOf[CodegenSupport].produce(ctx, this)
    } else null

    ctx.addMutableState("scala.collection.Iterator", ctx.columnarItrName,
       s"${ctx.columnarItrName} = null;")
    ctx.addMutableState("Object", ctx.columnarBatchName, s"${ctx.columnarBatchName} = null;")
    ctx.addMutableState("int", ctx.columnarBatchIdxName, s"${ctx.columnarBatchIdxName} = 0;")

    val codeProcess = if (codeCol == null) {
      s"""
      protected void processNext() throws java.io.IOException {
        ${codeRow.trim}
      }
     """
    } else {
      s"""
      private void processBatch() throws java.io.IOException {
        ${codeCol.trim}
      }

      private void processRow() throws java.io.IOException {
        ${codeRow.trim}
      }

      private void cleanup() {
        ${ctx.columnarBatchName} = null;
        ${ctx.columnarItrName} = null;
        ${ctx.cleanupMutableStates()}
      }

      protected void processNext() throws java.io.IOException {
        org.apache.spark.sql.execution.columnar.ColumnarIterator columnItr = null;
        if (${ctx.columnarBatchName} != null) {
          columnItr = (org.apache.spark.sql.execution.columnar.ColumnarIterator)
            ${ctx.inputHolder};
          ${ctx.columnarItrName} = columnItr.getInput();
          processBatch();
        } else if (${ctx.inputHolder} instanceof
          org.apache.spark.sql.execution.columnar.ColumnarIterator &&
          ((columnItr = (org.apache.spark.sql.execution.columnar.ColumnarIterator)
              ${ctx.inputHolder}).isSupportColumnarCodeGen())) {
          ${ctx.columnarBatchIdxName} = 0;
          ${ctx.columnarItrName} = columnItr.getInput();
          processBatch();
        } else {
          processRow();
        }
      }
     """.trim
    }

    val source = s"""
      public Object generate(Object[] references) {
        return new GeneratedIterator(references);
      }

      /** Codegened pipeline for:
       * ${toCommentSafeString(child.treeString.trim)}
       */
      final class GeneratedIterator extends org.apache.spark.sql.execution.BufferedRowIterator {

        private Object[] references;
        ${ctx.declareMutableStates()}

        public GeneratedIterator(Object[] references) {
          this.references = references;
        }

        public void init(int index, scala.collection.Iterator inputs[]) {
          partitionIndex = index;
          ${ctx.initMutableStates()}
        }

        ${ctx.declareAddedFunctions()}

        ${codeProcess}
      }
      """.trim

    // try to compile, helpful for debug
    val cleanedSource = CodeFormatter.stripExtraNewLines(source)
    logDebug(s"\n${CodeFormatter.format(cleanedSource)}")
    CodeGenerator.compile(cleanedSource)
    (ctx, cleanedSource)
  }

  override def doExecute(): RDD[InternalRow] = {
    val (ctx, cleanedSource) = doCodeGen()
    val references = ctx.references.toArray

    val durationMs = longMetric("pipelineTime")

    val rdds = child.asInstanceOf[CodegenSupport].inputRDDs()
    assert(rdds.size <= 2, "Up to two input RDDs can be supported")
    if (rdds.length == 1) {
      rdds.head.mapPartitionsWithIndex { (index, iter) =>
        val clazz = CodeGenerator.compile(cleanedSource)
        val buffer = clazz.generate(references).asInstanceOf[BufferedRowIterator]
        buffer.init(index, Array(iter))
        new Iterator[InternalRow] {
          override def hasNext: Boolean = {
            val v = buffer.hasNext
            if (!v) durationMs += buffer.durationMs()
            v
          }
          override def next: InternalRow = buffer.next()
        }
      }
    } else {
      // Right now, we support up to two input RDDs.
      rdds.head.zipPartitions(rdds(1)) { (leftIter, rightIter) =>
        val partitionIndex = TaskContext.getPartitionId()
        val clazz = CodeGenerator.compile(cleanedSource)
        val buffer = clazz.generate(references).asInstanceOf[BufferedRowIterator]
        buffer.init(partitionIndex, Array(leftIter, rightIter))
        new Iterator[InternalRow] {
          override def hasNext: Boolean = {
            val v = buffer.hasNext
            if (!v) durationMs += buffer.durationMs()
            v
          }
          override def next: InternalRow = buffer.next()
        }
      }
    }
  }

  override def inputRDDs(): Seq[RDD[InternalRow]] = {
    throw new UnsupportedOperationException
  }

  override def doProduce(ctx: CodegenContext): String = {
    throw new UnsupportedOperationException
  }

  override def doConsume(ctx: CodegenContext, input: Seq[ExprCode], row: ExprCode): String = {
    val doCopy = if (ctx.copyResult) {
      ".copy()"
    } else {
      ""
    }
    s"""
      |${row.code}
      |append(${row.value}$doCopy);
     """.stripMargin.trim
  }

  override def innerChildren: Seq[SparkPlan] = {
    child :: Nil
  }

  private def collectInputs(plan: SparkPlan): Seq[SparkPlan] = plan match {
    case InputAdapter(c) => c :: Nil
    case other => other.children.flatMap(collectInputs)
  }

  override def treeChildren: Seq[SparkPlan] = {
    collectInputs(child)
  }

  override def simpleString: String = "WholeStageCodegen"
}


/**
 * Find the chained plans that support codegen, collapse them together as WholeStageCodegen.
 */
case class CollapseCodegenStages(conf: SQLConf) extends Rule[SparkPlan] {

  private def supportCodegen(e: Expression): Boolean = e match {
    case e: LeafExpression => true
    // CodegenFallback requires the input to be an InternalRow
    case e: CodegenFallback => false
    case _ => true
  }

  private def numOfNestedFields(dataType: DataType): Int = dataType match {
    case dt: StructType => dt.fields.map(f => numOfNestedFields(f.dataType)).sum
    case m: MapType => numOfNestedFields(m.keyType) + numOfNestedFields(m.valueType)
    case a: ArrayType => numOfNestedFields(a.elementType)
    case u: UserDefinedType[_] => numOfNestedFields(u.sqlType)
    case _ => 1
  }

  private def supportCodegen(plan: SparkPlan): Boolean = plan match {
    case plan: CodegenSupport if plan.supportCodegen =>
      val willFallback = plan.expressions.exists(_.find(e => !supportCodegen(e)).isDefined)
      // the generated code will be huge if there are too many columns
      val hasTooManyOutputFields =
        numOfNestedFields(plan.schema) > conf.wholeStageMaxNumFields
      val hasTooManyInputFields =
        plan.children.map(p => numOfNestedFields(p.schema)).exists(_ > conf.wholeStageMaxNumFields)
      !willFallback && !hasTooManyOutputFields && !hasTooManyInputFields
    case _ => false
  }

  /**
   * Inserts a InputAdapter on top of those that do not support codegen.
   */
  private def insertInputAdapter(plan: SparkPlan): SparkPlan = plan match {
    case j @ SortMergeJoinExec(_, _, _, _, left, right) if j.supportCodegen =>
      // The children of SortMergeJoin should do codegen separately.
      j.copy(left = InputAdapter(insertWholeStageCodegen(left)),
        right = InputAdapter(insertWholeStageCodegen(right)))
    case p if !supportCodegen(p) =>
      // collapse them recursively
      InputAdapter(insertWholeStageCodegen(p))
    case p =>
      p.withNewChildren(p.children.map(insertInputAdapter))
  }

  /**
   * Inserts a WholeStageCodegen on top of those that support codegen.
   */
  private def insertWholeStageCodegen(plan: SparkPlan): SparkPlan = plan match {
    // For operators that will output domain object, do not insert WholeStageCodegen for it as
    // domain object can not be written into unsafe row.
    case plan if plan.output.length == 1 && plan.output.head.dataType.isInstanceOf[ObjectType] =>
      plan.withNewChildren(plan.children.map(insertWholeStageCodegen))
    case plan: CodegenSupport if supportCodegen(plan) =>
      WholeStageCodegenExec(insertInputAdapter(plan))
    case other =>
      other.withNewChildren(other.children.map(insertWholeStageCodegen))
  }

  def apply(plan: SparkPlan): SparkPlan = {
    if (conf.wholeStageEnabled) {
      insertWholeStageCodegen(plan)
    } else {
      plan
    }
  }
}
