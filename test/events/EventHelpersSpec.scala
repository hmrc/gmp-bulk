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

import org.scalatestplus.play.PlaySpec
import uk.gov.hmrc.http.HeaderCarrier

class EventHelpersSpec extends PlaySpec {

  "EventHelpers" must {
    "createMultiEntry should create a string from a list of values and deduplicate" in {

      implicit val hc = new HeaderCarrier()
      val result = EventHelpers.createMultiEntry(List("S2730000B", "S2730000B", "S2730001B", "S2730002B"))

      result must be("S2730001B:1;S2730000B:2;S2730002B:1")
    }
  }

}
