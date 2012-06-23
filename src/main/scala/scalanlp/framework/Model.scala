package scalanlp.framework

import collection.GenTraversable
import breeze.optimize.BatchDiffFunction
import actors.threadpool.AtomicInteger
import breeze.util.{Index, Encoder}
import breeze.linalg._
import breeze.serialization._
import java.util.zip.GZIPOutputStream
import java.io.{ObjectOutputStream, FileOutputStream, BufferedOutputStream, File}

trait Model[Datum] {
  self =>
  type ExpectedCounts <: scalanlp.framework.ExpectedCounts[ExpectedCounts]
  type Inference <: scalanlp.framework.Inference[Datum] {
    type ExpectedCounts = self.ExpectedCounts
  }

  def featureIndex: Index[Feature]

  def numFeatures = featureIndex.size

  // just saves feature weights to disk as a serialized counter. The file is prefix.ser.gz
  def cacheFeatureWeights(weights: DenseVector[Double], prefix: String = "weights") {
    val ctr = Encoder.fromIndex(featureIndex).decode(weights)
    val out = new ObjectOutputStream(new BufferedOutputStream(new GZIPOutputStream(new FileOutputStream(new File(prefix + ".ser.gz")))))
    implicit val serFeature: DataSerialization.ReadWritable[Feature] = DataSerialization.naiveReadWritable
    DataSerialization.write(out, ctr:Counter[Feature, Double])
    out.close()
  }

  def initialValueForFeature(f: Feature): Double // = 0

  def inferenceFromWeights(weights: DenseVector[Double]): Inference

  def emptyCounts: ExpectedCounts

  def expectedCountsToObjective(ecounts: ExpectedCounts): (Double, DenseVector[Double])
}

/**
 *
 * @author dlwh
 */
class ModelObjective[Datum](val model: Model[Datum],
                            batchSelector: IndexedSeq[Int]=>GenTraversable[Datum],
                            val fullRange: IndexedSeq[Int]) extends BatchDiffFunction[DenseVector[Double]] {
  def this(model: Model[Datum], data: IndexedSeq[Datum]) = this(model,_.par.map(data), 0 until data.length)
  import model.{ExpectedCounts => _, _}

  type Builder = model.Inference

  // Selects a set of data to use
  protected def select(batch: IndexedSeq[Int]):GenTraversable[Datum] = batchSelector(batch)

  def initialWeightVector(randomize: Boolean): DenseVector[Double] = {
   val v = Encoder.fromIndex(featureIndex).tabulateDenseVector(f => model.initialValueForFeature(f))
    if(randomize) {
      v += DenseVector.rand(numFeatures) * 1E-6
    }
    v
  }

  var iter = 0

  def calculate(x: DenseVector[Double], batch: IndexedSeq[Int]) = {
    if(iter % 30 == 0) {
      model.cacheFeatureWeights(x, "weights")
    }
    iter += 1
    val inference = inferenceFromWeights(x)
    val timeIn = System.currentTimeMillis()
    val success = new AtomicInteger(0)
    val finalCounts = select(batch).aggregate(emptyCounts)({ (countsSoFar,datum) =>
      try {
        val counts = inference.expectedCounts(datum) += countsSoFar
        success.incrementAndGet()
        counts
      } catch {
        case e =>
          e.printStackTrace()
//          new Exception("While processing " + datum, e).printStackTrace()
          countsSoFar
      }
    },{ (a,b) => b += a})
    val timeOut = System.currentTimeMillis()
    println("Parsing took: " + (timeOut - timeIn) * 1.0/1000 + "s" )

    val (loss,grad) = expectedCountsToObjective(finalCounts)
    val timeOut2 = System.currentTimeMillis()
    println("Finishing took: " + (timeOut2 - timeOut) * 1.0/1000 + "s" )
    (loss/success.intValue() * fullRange.size,  grad * (fullRange.size * 1.0 / success.intValue))
  }
}

trait ExpectedCounts[Self<:ExpectedCounts[Self]] { this:Self =>
  def +=(other: Self):Self
  def -=(other: Self):Self
  def loss: Double
}