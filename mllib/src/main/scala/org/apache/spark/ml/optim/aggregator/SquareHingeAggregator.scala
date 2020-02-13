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

package org.apache.spark.ml.optim.aggregator

import org.apache.spark.broadcast.Broadcast
import org.apache.spark.ml.feature.Instance
import org.apache.spark.ml.linalg._

/**
 * SquaredHingeAggregator computes the gradient and loss for squared Hinge loss function, as used in
 * binary classification for instances in sparse or dense vector in an online fashion.
 *
 * Two SquaredHingeAggregator can be merged together to have a summary of loss and gradient of
 * the corresponding joint dataset.
 *
 * This class standardizes feature values during computation using bcFeaturesStd.
 *
 * @param bcCoefficients The coefficients corresponding to the features.
 * @param fitIntercept Whether to fit an intercept term.
 * @param bcFeaturesStd The standard deviation values of the features.
 */
private[ml] class SquaredHingeAggregator(
    bcFeaturesStd: Broadcast[Array[Double]],
    fitIntercept: Boolean)(bcCoefficients: Broadcast[Vector])
  extends DifferentiableLossAggregator[Instance, SquaredHingeAggregator] {

  private val numFeatures: Int = bcFeaturesStd.value.length
  private val numFeaturesPlusIntercept: Int = if (fitIntercept) numFeatures + 1 else numFeatures
  @transient private lazy val coefficientsArray = bcCoefficients.value match {
    case DenseVector(values) => values
    case _ => throw new IllegalArgumentException(s"coefficients only supports dense vector" +
      s" but got type ${bcCoefficients.value.getClass}.")
  }
  protected override val dim: Int = numFeaturesPlusIntercept

  /**
   * Add a new training instance to this SquaredHingeAggregator, and update the loss and gradient
   * of the objective function.
   *
   * @param instance The instance of data point to be added.
   * @return This SquaredHingeAggregator object.
   */
  def add(instance: Instance): this.type = {
    instance match { case Instance(label, weight, features) =>
      require(numFeatures == features.size, s"Dimensions mismatch when adding new instance." +
        s" Expecting $numFeatures but got ${features.size}.")
      require(weight >= 0.0, s"instance weight, $weight has to be >= 0.0")

      if (weight == 0.0) return this
      val localFeaturesStd = bcFeaturesStd.value
      val localCoefficients = coefficientsArray
      val localGradientSumArray = gradientSumArray

      val dotProduct = {
        var sum = 0.0
        features.foreachActive { (index, value) =>
          if (localFeaturesStd(index) != 0.0 && value != 0.0) {
            sum += localCoefficients(index) * value / localFeaturesStd(index)
          }
        }
        if (fitIntercept) sum += localCoefficients(numFeaturesPlusIntercept - 1)
        sum
      }

      val labelScaled = 2 * label - 1.0
      val scaledDoctProduct = labelScaled * dotProduct
      val loss = if (1.0 > scaledDoctProduct) {
        val hingeLoss = 1.0 - scaledDoctProduct
        hingeLoss * hingeLoss * weight
      } else {
        0.0
      }

      if (1.0 > scaledDoctProduct) {
        val gradientScale = (scaledDoctProduct - 1) * labelScaled * 2 * weight
        features.foreachActive { (index, value) =>
          if (localFeaturesStd(index) != 0.0 && value != 0.0) {
            localGradientSumArray(index) += value * gradientScale / localFeaturesStd(index)
          }
        }
        if (fitIntercept) {
          localGradientSumArray(localGradientSumArray.length - 1) += gradientScale
        }
      }
      lossSum += loss
      weightSum += weight
      this
    }
  }
}
