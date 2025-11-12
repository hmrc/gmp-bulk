/*
 * Copyright 2025 HM Revenue & Customs
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

package models

import play.api.libs.functional.syntax._
import play.api.libs.json._

case class HipFailure(reason: String, code: Int)

object HipFailure {
  private def parseCode(js: JsValue): Int = js match {
    case JsNumber(n) => n.toInt
    case JsString(s) => s.toIntOption.getOrElse(0)
    case _           => 0
  }

  implicit val reads: Reads[HipFailure] = (
    (JsPath \ "reason").read[String] and
      (JsPath \ "code").read[JsValue].map(parseCode)
    )(HipFailure.apply)

  implicit val writes: OWrites[HipFailure] = Json.writes[HipFailure]
}

case class HipCalculationFailuresResponse(failures: List[HipFailure])

object HipCalculationFailuresResponse {
  implicit val formats: OFormat[HipCalculationFailuresResponse] = Json.format[HipCalculationFailuresResponse]
}
