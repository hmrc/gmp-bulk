/*
 * Copyright 2020 HM Revenue & Customs
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

package events

import uk.gov.hmrc.http.HeaderCarrier

class BulkEvent(userId: String,
                successfulCount: Int,
                failedValidationCount: Int,
                failedNPSCount: Int,
                rowCount: Int,
                errorCodes: List[Int],
                scons: List[String],
                dualCalcs: List[Boolean],
                calcTypes: List[Int]) (implicit hc: HeaderCarrier)
  extends GmpBulkBusinessEvent("GMP-Bulk-Results",
    Map("userId" -> userId.toString,
        "calcCount" -> (successfulCount + failedNPSCount).toString,
        "successfulCount" -> successfulCount.toString,
        "failedValidationCount" -> failedValidationCount.toString,
        "failedNPSCount" -> failedNPSCount.toString,
        "rowCount" -> rowCount.toString,
        "errorCodes" -> EventHelpers.createMultiEntry(errorCodes),
        "scons" -> EventHelpers.createMultiEntry(scons),
        "dualCalcs" -> EventHelpers.createMultiEntry(dualCalcs),
        "calcTypes" -> EventHelpers.createMultiEntry(calcTypes)
    ))
