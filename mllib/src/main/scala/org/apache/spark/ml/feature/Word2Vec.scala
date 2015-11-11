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

package org.apache.spark.ml.feature

import org.apache.spark.SparkContext
import org.apache.spark.annotation.Experimental
import org.apache.spark.ml.{Estimator, Model}
import org.apache.spark.ml.param._
import org.apache.spark.ml.param.shared._
import org.apache.spark.ml.util.{Identifiable, SchemaUtils}
import org.apache.spark.mllib.feature
import org.apache.spark.mllib.linalg.{BLAS, Vector, VectorUDT, Vectors}
import org.apache.spark.sql.{DataFrame, SQLContext}
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types._

/**
 * Params for [[Word2Vec]] and [[Word2VecModel]].
 */
private[feature] trait Word2VecBase extends Params
  with HasInputCol with HasOutputCol with HasMaxIter with HasStepSize with HasSeed {

  /**
   * The dimension of the code that you want to transform from words.
   * Default: 100
   * @group param
   */
  final val vectorSize = new IntParam(
    this, "vectorSize", "the dimension of codes after transforming from words")
  setDefault(vectorSize -> 100)

  /** @group getParam */
  def getVectorSize: Int = $(vectorSize)

  /**
   * Number of partitions for sentences of words.
   * Default: 1
   * @group param
   */
  final val numPartitions = new IntParam(
    this, "numPartitions", "number of partitions for sentences of words")
  setDefault(numPartitions -> 1)

  /** @group getParam */
  def getNumPartitions: Int = $(numPartitions)

  /**
   * The minimum number of times a token must appear to be included in the word2vec model's
   * vocabulary.
   * Default: 5
   * @group param
   */
  final val minCount = new IntParam(this, "minCount", "the minimum number of times a token must " +
    "appear to be included in the word2vec model's vocabulary")
  setDefault(minCount -> 5)

  /** @group getParam */
  def getMinCount: Int = $(minCount)

  setDefault(stepSize -> 0.025)
  setDefault(maxIter -> 1)

  /**
   * Validate and transform the input schema.
   */
  protected def validateAndTransformSchema(schema: StructType): StructType = {
    SchemaUtils.checkColumnType(schema, $(inputCol), new ArrayType(StringType, true))
    SchemaUtils.appendColumn(schema, $(outputCol), new VectorUDT)
  }
}

/**
 * :: Experimental ::
 * Word2Vec trains a model of `Map(String, Vector)`, i.e. transforms a word into a code for further
 * natural language processing or machine learning process.
 */
@Experimental
final class Word2Vec(override val uid: String) extends Estimator[Word2VecModel] with Word2VecBase {

  def this() = this(Identifiable.randomUID("w2v"))

  /** @group setParam */
  def setInputCol(value: String): this.type = set(inputCol, value)

  /** @group setParam */
  def setOutputCol(value: String): this.type = set(outputCol, value)

  /** @group setParam */
  def setVectorSize(value: Int): this.type = set(vectorSize, value)

  /** @group setParam */
  def setStepSize(value: Double): this.type = set(stepSize, value)

  /** @group setParam */
  def setNumPartitions(value: Int): this.type = set(numPartitions, value)

  /** @group setParam */
  def setMaxIter(value: Int): this.type = set(maxIter, value)

  /** @group setParam */
  def setSeed(value: Long): this.type = set(seed, value)

  /** @group setParam */
  def setMinCount(value: Int): this.type = set(minCount, value)

  override def fit(dataset: DataFrame): Word2VecModel = {
    transformSchema(dataset.schema, logging = true)
    val input = dataset.select($(inputCol)).map(_.getAs[Seq[String]](0))
    val wordVectors = new feature.Word2Vec()
      .setLearningRate($(stepSize))
      .setMinCount($(minCount))
      .setNumIterations($(maxIter))
      .setNumPartitions($(numPartitions))
      .setSeed($(seed))
      .setVectorSize($(vectorSize))
      .fit(input)
    copyValues(new Word2VecModel(uid, wordVectors).setParent(this))
  }

  override def transformSchema(schema: StructType): StructType = {
    validateAndTransformSchema(schema)
  }

  override def copy(extra: ParamMap): Word2Vec = defaultCopy(extra)
}

/**
 * :: Experimental ::
 * Model fitted by [[Word2Vec]].
 */
@Experimental
class Word2VecModel private[ml] (
    override val uid: String,
    @transient wordVectors: feature.Word2VecModel)
  extends Model[Word2VecModel] with Word2VecBase {

  /**
   * Returns a dataframe with two fields, "word" and "vector", with "word" being a String and
   * and the vector the DenseVector that it is mapped to.
   */
  @transient lazy val getVectors: DataFrame = {
    val sc = SparkContext.getOrCreate()
    val sqlContext = SQLContext.getOrCreate(sc)
    import sqlContext.implicits._
    val wordVec = wordVectors.getVectors.mapValues(vec => Vectors.dense(vec.map(_.toDouble)))
    sc.parallelize(wordVec.toSeq).toDF("word", "vector")
  }

  /**
   * Find "num" number of words closest in similarity to the given word.
   * Returns a dataframe with the words and the cosine similarities between the
   * synonyms and the given word.
   */
  def findSynonyms(word: String, num: Int): DataFrame = {
    findSynonyms(wordVectors.transform(word), num)
  }

  /**
   * Find "num" number of words closest to similarity to the given vector representation
   * of the word. Returns a dataframe with the words and the cosine similarities between the
   * synonyms and the given word vector.
   */
  def findSynonyms(word: Vector, num: Int): DataFrame = {
    val sc = SparkContext.getOrCreate()
    val sqlContext = SQLContext.getOrCreate(sc)
    import sqlContext.implicits._
    sc.parallelize(wordVectors.findSynonyms(word, num)).toDF("word", "similarity")
  }

  /** @group setParam */
  def setInputCol(value: String): this.type = set(inputCol, value)

  /** @group setParam */
  def setOutputCol(value: String): this.type = set(outputCol, value)

  /**
   * Transform a sentence column to a vector column to represent the whole sentence. The transform
   * is performed by averaging all word vectors it contains.
   */
  override def transform(dataset: DataFrame): DataFrame = {
    transformSchema(dataset.schema, logging = true)
    val vectors = wordVectors.getVectors
      .mapValues(vv => Vectors.dense(vv.map(_.toDouble)))
      .map(identity) // mapValues doesn't return a serializable map (SI-7005)
    val bVectors = dataset.sqlContext.sparkContext.broadcast(vectors)
    val d = $(vectorSize)
    val word2Vec = udf { sentence: Seq[String] =>
      if (sentence.size == 0) {
        Vectors.sparse(d, Array.empty[Int], Array.empty[Double])
      } else {
        val sum = Vectors.zeros(d)
        sentence.foreach { word =>
          bVectors.value.get(word).foreach { v =>
            BLAS.axpy(1.0, v, sum)
          }
        }
        BLAS.scal(1.0 / sentence.size, sum)
        sum
      }
    }
    dataset.withColumn($(outputCol), word2Vec(col($(inputCol))))
  }

  override def transformSchema(schema: StructType): StructType = {
    validateAndTransformSchema(schema)
  }

  override def copy(extra: ParamMap): Word2VecModel = {
    val copied = new Word2VecModel(uid, wordVectors)
    copyValues(copied, extra).setParent(parent)
  }
}
