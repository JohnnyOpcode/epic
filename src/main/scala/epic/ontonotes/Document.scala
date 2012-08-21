package epic.ontonotes


/*
 Copyright 2012 David Hall

 Licensed under the Apache License, Version 2.0 (the "License")
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
*/

/**
 * Represents an ontonotes document (a single file)
 */
case class Document(id: String, sentences: Seq[Sentence])

object Document {
  /**
   * Reads a single document reprseented as the XML file format
   * <document id="..."> <sentence> ... </sentence> </document>
   */
  def fromXML(node: xml.Node) = {
    Document(node \ "@id" text, node \ "sentence" map {Sentence.fromXML _})
  }
}