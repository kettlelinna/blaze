/*
 * Copyright 2022 The Blaze Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.execution.blaze.plan

import scala.collection.JavaConverters._

import org.apache.spark.OneToOneDependency
import org.apache.spark.Partition
import org.blaze.{protobuf => pb}

import org.apache.spark.sql.blaze.MetricNode
import org.apache.spark.sql.blaze.NativeConverters
import org.apache.spark.sql.blaze.NativeHelper
import org.apache.spark.sql.blaze.NativeRDD
import org.apache.spark.sql.blaze.NativeSupports
import org.apache.spark.sql.catalyst.expressions.Attribute
import org.apache.spark.sql.catalyst.expressions.Expression
import org.apache.spark.sql.catalyst.expressions.SortOrder
import org.apache.spark.sql.catalyst.plans.JoinType
import org.apache.spark.sql.catalyst.plans.physical.Partitioning
import org.apache.spark.sql.execution.BinaryExecNode
import org.apache.spark.sql.execution.SparkPlan
import org.apache.spark.sql.execution.joins.BroadcastHashJoinExec
import org.apache.spark.sql.execution.joins.BuildLeft
import org.apache.spark.sql.execution.metric.SQLMetric

case class NativeBroadcastJoinExec(
    override val left: SparkPlan,
    override val right: SparkPlan,
    leftKeys: Seq[Expression],
    rightKeys: Seq[Expression],
    override val output: Seq[Attribute],
    override val outputPartitioning: Partitioning,
    override val outputOrdering: Seq[SortOrder],
    joinType: JoinType)
    extends BinaryExecNode
    with NativeSupports {

  override lazy val metrics: Map[String, SQLMetric] = Map(
    NativeHelper
      .getDefaultNativeMetrics(sparkContext)
      .filterKeys(Set("output_rows", "elapsed_compute"))
      .toSeq: _*)

  private val nativeJoinOn = leftKeys.zip(rightKeys).map {
    case (leftKey, rightKey) =>
      val leftColumn = NativeConverters.convertExpr(leftKey).getColumn match {
        case column if column.getName.isEmpty =>
          throw new NotImplementedError(s"BHJ leftKey is not column: ${leftKey}")
        case column => column
      }
      val rightColumn = NativeConverters.convertExpr(rightKey).getColumn match {
        case column if column.getName.isEmpty =>
          throw new NotImplementedError(s"BHJ leftKey is not column: ${rightKey}")
        case column => column
      }
      pb.JoinOn
        .newBuilder()
        .setLeft(leftColumn)
        .setRight(rightColumn)
        .build()
  }

  private val nativeJoinType = NativeConverters.convertJoinType(joinType)

  override def doExecuteNative(): NativeRDD = {
    val leftRDD = NativeHelper.executeNative(left)
    val rightRDD = NativeHelper.executeNative(right)
    val nativeMetrics = MetricNode(metrics, leftRDD.metrics :: rightRDD.metrics :: Nil)
    val partitions = rightRDD.partitions

    new NativeRDD(
      sparkContext,
      nativeMetrics,
      partitions,
      rddDependencies = new OneToOneDependency(rightRDD) :: Nil,
      rightRDD.isShuffleReadFull,
      (partition, context) => {
        val partition0 = new Partition() {
          override def index: Int = 0
        }
        val leftChild = leftRDD.nativePlan(partition0, context)
        val rightChild = rightRDD.nativePlan(rightRDD.partitions(partition.index), context)
        val broadcastJoinExec = pb.BroadcastJoinExecNode
          .newBuilder()
          .setLeft(leftChild)
          .setRight(rightChild)
          .setJoinType(nativeJoinType)
          .addAllOn(nativeJoinOn.asJava)
        pb.PhysicalPlanNode.newBuilder().setBroadcastJoin(broadcastJoinExec).build()
      },
      friendlyName = "NativeRDD.BroadcastJoin")
  }

  override def doCanonicalize(): SparkPlan =
    BroadcastHashJoinExec(
      leftKeys,
      rightKeys,
      joinType,
      buildSide = BuildLeft,
      condition = None,
      left,
      right).canonicalized

  override def withNewChildren(newChildren: Seq[SparkPlan]): SparkPlan =
    copy(left = newChildren(0), right = newChildren(1))
}