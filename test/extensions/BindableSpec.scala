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

package extensions

import extensions.Binders.CsvFilterBindable
import models.CsvFilter
import org.scalatestplus.play.PlaySpec

class BindableSpec extends PlaySpec {

  "CsvFilterBindable" must {

    "create a CsvFilter instance given a filter string" in {

      val bindable = CsvFilterBindable.bind("filter", "successful")

      bindable.right.get.filterType must be("SUCCESSFUL")
    }

    "return the filter string when unbound" in {
      val bindable = CsvFilter.Failed
      val filter = CsvFilterBindable.unbind("unused", bindable)

      filter must be("FAILED")
    }
  }
}
