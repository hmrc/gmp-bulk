GMP-BULK
=============

Guaranteed Minimum Pension Bulk micro service

[![Build Status](https://travis-ci.org/hmrc/gmp-bulk.svg)](https://travis-ci.org/hmrc/gmp-bulk) [ ![Download](https://api.bintray.com/packages/hmrc/releases/gmp-bulk/images/download.svg) ](https://bintray.com/hmrc/releases/gmp-bulk/_latestVersion)

##API

### Dependency Upgrades

use command dependencyUpdates in SBT to give a list of all potential dependency upgrades.

addSbtPlugin("com.timushev.sbt" % "sbt-updates" % "0.5.0")

| PATH | Supported Methods | Description |
|------|-------------------|-------------|
| ```/gmp/bulk-data``` | POST | Post a bulk calculation |

### POST /gmp/bulk-data
Post multiple validated calculations to be evaluated.

### Responds with
| Status                                                   |  Description                                   |
|----------------------------------------------------------|------------------------------------------------|
| 400                                                      |  Bad Request                                   |
| 200                                                      |  Successful Post of a bulk calculation request |

##### Example of usage

**Response body**

```json
              {
                "uploadReference" : "UPLOAD1234",
                "email" : "test@test.com",
                "reference" : "REF1234",
                "calculationRequests" : [
                  {
                    "lineId": 1,
                    "validCalculation": {
                      "scon" : "S2730000B",
                      "nino" : "nino",
                      "surname" : "surname",
                      "firstForename": "firstname",
                      "memberReference": "MEMREF123",
                      "calctype" : 1,
                      "revaluationDate": "2018-01-01",
                      "revaluationRate": 2,
                      "requestEarnings": 1,
                      "dualCalc" : 1,
                      "terminationDate" : "2016-07-07"
                    }
                  },
                  {
                    "lineId" : 2,
                    "validationError" : "No scon supplied"
                  },
                  {
                    "lineId" : 3,
                    "validCalculation": {
                      "scon" : "S2730000B",
                      "nino" : "nino",
                      "surname" : "lastname",
                      "firstForename": "firstname",
                      "calctype" : 0
                    }
                  },
                  {
                    "lineId" : 4,
                    "validationError" : "Invalid scon format"
                  }]
              }
```


