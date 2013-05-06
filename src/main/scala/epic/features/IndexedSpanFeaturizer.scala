package epic.features

import epic.sequences.Gazetteer
import breeze.util.Index
import epic.framework.Feature
import breeze.linalg._
import scala.collection.mutable.ArrayBuffer
import breeze.collection.mutable.{OpenAddressHashArray, TriangularArray}

/**
 *
 * @author dlwh
 */
class IndexedSpanFeaturizer(val featurizer: BasicSpanFeaturizer,
                            val wordFeatureIndex: Index[Feature],
                            val spanFeatureIndex: Index[Feature],
                            wordCounts: Counter[String, Double],
                            prevCurFeature: (Int,Int)=>Int,
                            curNextFeature: (Int,Int)=>Int,
                            prevNextFeature: (Int,Int)=>Int,
                            asLeftFeature: Array[Int],
                            asRightFeature: Array[Int],
                            needsContextFeatures: (String,Double)=>Boolean = {(w,c) => true}){
  def anchor(words: IndexedSeq[String], validSpan: (Int,Int)=>Boolean = {(_, _) => true}) = new Localization(words, validSpan)

  private val emptyArray = Array.empty[Int]

  class Localization(words: IndexedSeq[String], validSpan: (Int,Int)=>Boolean = {(_, _) => true}) {
    def basicFeatures(pos: Int): Array[Int] = spanFeaturizer.basicFeaturesForWord(pos)
    def featuresForWord(pos: Int): Array[Int] = _featuresForWord(pos)
    def featuresForSpan(beg: Int, end: Int): Array[Int] = _featuresForSpan(beg, end)
    val spanFeaturizer = featurizer.anchor(words)

    private val _featuresForWord: IndexedSeq[Array[Int]] = 0 until words.length map { pos =>
      val wc = wordCounts(words(pos))
      val basic = basicFeatures(pos)
      if(!needsContextFeatures(words(pos), wc)) {
        basic
      } else {
        val feats = new ArrayBuffer[Int]()
        val basicLeft = basicFeatures(pos - 1)
        val basicRight = basicFeatures(pos + 1)
        feats.sizeHint((basic.length + 1) * (basicLeft.length + basicRight.length + 1) + basicLeft.length * basicRight.length)
        feats ++= spanFeaturizer.fullFeaturesForWord(pos)
        feats ++= basicLeft.map(asLeftFeature)
        feats ++= basicRight.map(asRightFeature)
        //        feats ++= inner.featuresFor(words, pos)
        for (a <- basicLeft; b <- basic) {
          val fi = prevCurFeature(a,b)
          if(fi >= 0)
            feats += fi
        }
        for (a <- basic; b <- basicRight) {
          val fi = curNextFeature(a,b)
          if(fi >= 0)
            feats += fi
        }
        for (a <- basicLeft; b <- basicRight) {
          val fi = prevNextFeature(a,b)
          if(fi >= 0)
            feats += fi
        }
        //          feats += TrigramFeature(basicLeft(0), basic(0), basicRight(0))
        //          if (pos > 0 && pos < words.length - 1) {
        //            feats += TrigramFeature(shapes(pos-1), shapes(pos), shapes(pos+1))
        //            feats += TrigramFeature(classes(pos-1), classes(pos), classes(pos+1))
        //          }
        feats.toArray
      }
    }

    private val _featuresForSpan = TriangularArray.tabulate(words.length+1) { (beg,end) =>
      if(beg == end || !validSpan(beg,end)) emptyArray
      else {
        spanFeaturizer.featuresForSpan(beg, end).map(spanFeatureIndex(_)).filter(_ != -1)
      }
    }

  }
}

object IndexedSpanFeaturizer {
  def forTrainingSet[L](corpus: Iterable[(IndexedSeq[String], (Int,Int)=>Boolean)],
                        tagWordCounts: Counter2[L, String, Double],
                        gazetteer: Gazetteer[Any, String] = Gazetteer.empty,
                        noShapeThreshold: Int = 100,
                        needsContextFeatures: (String,Double)=>Boolean = {(w,c) => true}):IndexedSpanFeaturizer = {
    val wordCounts = sum(tagWordCounts, Axis._0)
    val featureIndex = Index[Feature]()
    val spanFeatureIndex = Index[Feature]()

    val feat = new BasicSpanFeaturizer(new BasicWordFeaturizer(tagWordCounts, wordCounts, gazetteer, noShapeThreshold))
    val basicFeatureIndex = feat.wordFeatureIndex

    // for left and right
    val asLeftFeatures = new Array[Int](basicFeatureIndex.size)
    val asRightFeatures = new Array[Int](basicFeatureIndex.size)

    for( (f, fi) <- basicFeatureIndex.pairs) {
      asLeftFeatures(fi) = featureIndex.index(PrevWordFeature(f))
      asRightFeatures(fi) = featureIndex.index(NextWordFeature(f))
    }

    val prevCurBigramFeatures = Array.fill(basicFeatureIndex.size)(new OpenAddressHashArray[Int](basicFeatureIndex.size, default= -1))
    val curNextBigramFeatures = Array.fill(basicFeatureIndex.size)(new OpenAddressHashArray[Int](basicFeatureIndex.size, default= -1))
    val prevNextBigramFeatures = Array.fill(basicFeatureIndex.size)(new OpenAddressHashArray[Int](basicFeatureIndex.size, default= -1))

    for( (words, validSpan) <- corpus) {
      val anch = feat.anchor(words)

      def bf(pos: Int) =  anch.basicFeaturesForWord(pos)

      // words
      for(pos <- 0 until words.length) {
        val wc = wordCounts(words(pos))
        val basic = bf(pos)
        if(!needsContextFeatures(words(pos), wc)) {
          basic
        } else {
          val basicLeft = bf(pos - 1)
          val basicRight = bf(pos + 1)
          for (a <- basicLeft; b <- basic)  prevCurBigramFeatures(a)(b) = featureIndex.index(BigramFeature(featureIndex.get(asLeftFeatures(a)),featureIndex.get(b)))
          for (a <- basic; b <- basicRight) curNextBigramFeatures(a)(b) = featureIndex.index(BigramFeature(featureIndex.get(a),featureIndex.get(asRightFeatures(b))))
          for (a <- basicLeft; b <- basicRight) prevNextBigramFeatures(a)(b) = featureIndex.index(BigramFeature(featureIndex.get(asLeftFeatures(a)), featureIndex.get(asRightFeatures(b))))
          //          feats += TrigramFeature(basicLeft(0), basic(0), basicRight(0))
          //          if (pos > 0 && pos < words.length - 1) {
          //            feats += TrigramFeature(shapes(pos-1), shapes(pos), shapes(pos+1))
          //            feats += TrigramFeature(classes(pos-1), classes(pos), classes(pos+1))
          //          }
        }
      }

      // spans
      for( begin <- 0 until words.length; end <- (begin+1) to words.length if validSpan(begin, end)) {
        anch.featuresForSpan(begin, end).foreach(spanFeatureIndex.index(_))
      }

    }

    new IndexedSpanFeaturizer(feat,
    featureIndex, spanFeatureIndex, wordCounts,
    {(p,c) =>prevCurBigramFeatures(p)(c)},
    {(c,r) =>curNextBigramFeatures(c)(r)},
    {(p,r) =>prevNextBigramFeatures(p)(r)},
    asLeftFeatures, asRightFeatures,
    needsContextFeatures)
  }
}
