/*
 * Copyright 2022 HM Revenue & Customs
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

import org.scalatestplus.play.PlaySpec

class CsvFilterTypeSpec extends PlaySpec {

  "CsvFilterType" must {
    "return correct filename for all filter" in {
      val filter = CsvFilter("ALL")
      filter.getFileTypeName must be("all")
    }

    "return correct filename for successful filter" in {
      val filter = CsvFilter("SUCCESSFUL")
      filter.getFileTypeName must be("total_GMP")
    }

    "return correct filename for failed filter" in {
      val filter = CsvFilter("FAILED")
      filter.getFileTypeName must be("no_total_GMP")
    }
  }
}
