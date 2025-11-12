GMP-BULK
=============

Guaranteed Minimum Pension Bulk micro service


| PATH | Supported Methods | Description |
|------|-------------------|-------------|
| ```/gmp/bulk-data``` | POST | Post a bulk calculation |

### POST /gmp/bulk-data
Post multiple validated calculations to be evaluated.

### Responds with
| Status | Description |
|--------|-------------|
| 200 | Successful post of a bulk calculation request |
| 400 | Bad Request (invalid/malformed JSON or body validation failure) |
| 401 | Unauthorized (auth failure via `AuthAction`) |
| 403 | Forbidden (user not permitted to access resource) |
| 5xx | Internal Server Error |

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

## HIP API Integration (Spec 1.0.5)

- **Success (200 OK)**: HIP returns `HipCalculationResponse`. We transform it via `GmpBulkCalculationResponse.createFromHipResponse(...)`.
  - `globalErrorCode = 0` for 200 success.
  - Any errors are represented by period-level error codes inside `calculationPeriods`.

- **Failures (422 Unprocessable Entity)**: HIP returns `HipCalculationFailuresResponse`. We transform it via `GmpBulkCalculationResponse.createFromHipFailuresResponse(...)`.
  - `globalErrorCode = first HIP failure code` (e.g., 63123).
  - `calculationPeriods` is empty in this case.

### Request mapping to HIP
- Source model: `models/ValidCalculationRequest` → `models/HipCalculationRequest` (see `app/models/HipCalculationRequest.scala`).
- Mappings:
  - `revaluationRate`: 0 → `(NONE)`, 1 → `S148`, 2 → `FIXED`, 3 → `LIMITED`.
  - `calctype`: 0 → `DOL`, 1 → `Revaluation`, 2 → `PayableAge`, 3 → `Survivor`, 4 → `SPA`.
  - Missing dates map to the literal string `"(NONE)"`.
  - `dualCalc`: `Some(1)` → `includeDualCalculation = true`; otherwise `false`.

### Error code mapping
- Only GMP error message strings are mapped to integers via `HipErrorCodeMapper.mapGmpErrorCode` (`app/utils/HipErrorCodeMapper.scala`).
- Legacy rejection-reason mapping is removed; 422 failures use the dedicated failures model.

### HIP headers and authentication
Defined in `app/connectors/HipConnector.scala`:
- `gov-uk-originator-id` (from config), `correlationId` (UUID), `Authorization: Basic <token>`.
- Environment header, `X-Originating-System`, `X-Receipt-Date`, `X-Transmitting-System`.

### Circuit breaker and error handling
- Circuit breaker trips on 5xx/timeouts from HIP (`UsingCircuitBreaker`).
- `429` rate limiting logs a warning and short-circuits via breaker.
- Non-200/422 responses surface as `UpstreamErrorResponse` with appropriate `reportAs` codes.
- HIP 503 is persisted as a failure even when surfaced via circuit breaker (globalErrorCode = 503).

### HIP error handling parity (service behavior)
- On HIP responses, the service now persists a failure row with `globalErrorCode` equal to the status for:
  - 400, 403, 404, 500, 503
- HIP 422 (`HipCalculationFailuresResponse`) is transformed via `GmpBulkCalculationResponse.createFromHipFailuresResponse(...)` with:
  - `globalErrorCode = first HIP failure code`
  - `calculationPeriods = []`
- 200 HIP success is transformed via `GmpBulkCalculationResponse.createFromHipCalculationResponse(...)` with:
  - `globalErrorCode = 0`, period-level errors mapped from HIP messages
- Correlation ID (if present) from HIP/IF responses is logged alongside failures for troubleshooting.

### Auditing
- Requests are audited with `AuditConnector.sendEvent(DataEvent(...))`; audit failures are logged as warnings.

### Logging and redaction
- Response/request bodies are redacted before logging via `utils.LoggingUtils.redactCalculationData`:
  - Masks `nino`, `scon`, and any fields containing `name`/`surname`.
  - Non-JSON fallback masks digits, emails and limits length to 100 chars.

## Legacy Mode (DES / IF)

This service can operate against legacy backends when HIP is disabled.

### Routing and feature flags
- Controlled in `app/actors/CalculationRequestActor.scala`.
- Preference:
  1. IF when `appConfig.isIfsEnabled = true` → `app/connectors/IFConnector.scala`
  2. HIP when `appConfig.isHipEnabled = true` → `app/connectors/HipConnector.scala`
  3. Otherwise DES → `app/connectors/DesConnector.scala`

### Responses and persistence
- Success (DES/IF): mapped via `GmpBulkCalculationResponse.createFromCalculationResponse(...)` (DES) or HIP paths above.
- Failures:
  - HIP: 400/403/404/500/503 persisted as failure rows (as above).
  - DES: 400/500 persisted; 423 Hidden Record handled via `getPersonDetails` and persisted.
  - Other errors may bubble as failures depending on connector behavior.

### Examples

- **ValidCalculationRequest → HipCalculationRequest**

```json
// ValidCalculationRequest (input)
{
  "scon": "S1234567T",
  "nino": "AA123456A",
  "surname": "lewis",
  "firstForename": "stan",
  "memberReference": "MEM1",
  "calctype": 2,
  "revaluationRate": 1,
  "revaluationDate": "2022-06-01",
  "terminationDate": "2022-06-30",
  "dualCalc": 1
}

// HipCalculationRequest (sent to HIP)
{
  "schemeContractedOutNumber": "S1234567T",
  "nationalInsuranceNumber": "AA123456A",
  "surname": "lewis",
  "firstForename": "stan",
  "secondForename": null,
  "revaluationRate": "S148",
  "calculationRequestType": "Payable Age Calculation",
  "revaluationDate": "2022-06-01",
  "terminationDate": "2022-06-30",
  "includeContributionAndEarnings": true,
  "includeDualCalculation": true
}
```

- **HIP 200 success → GmpBulkCalculationResponse**

```json
// HIP 200 success (HipCalculationResponse)
{
  "nationalInsuranceNumber": "AA123456A",
  "schemeContractedOutNumberDetails": "S1234567T",
  "payableAgeDate": null,
  "statePensionAgeDate": null,
  "dateOfDeath": null,
  "GuaranteedMinimumPensionDetailsList": [
    {
      "schemeMembershipStartDate": null,
      "schemeMembershipEndDate": "2022-06-30",
      "revaluationRate": "S148",
      "post1988GMPContractedOutDeductionsValue": 0,
      "gmpContractedOutDeductionsAllRateValue": 0,
      "gmpErrorCode": "Input revaluation date is before the termination date held on hmrc records",
      "revaluationCalculationSwitchIndicator": false,
      "post1990GMPContractedOutTrueSexTotal": null,
      "post1990GMPContractedOutOppositeSexTotal": null,
      "inflationProofBeyondDateofDeath": false,
      "contributionsAndEarningsDetailsList": [
        { "taxYear": 2022, "contributionOrEarningsAmount": 11.2 }
      ]
    }
  ]
}

// Transformed GmpBulkCalculationResponse (globalErrorCode = 0; period error populated)
{
  "periods": [
    {
      "errorCode": 63123,
      "contsAndEarnings": [ { "taxYear": 2022, "contEarnings": "11" } ]
    }
  ],
  "globalErrorCode": 0,
  "containsErrors": true
}
```

- **HIP 422 failures → GmpBulkCalculationResponse**

```json
// HIP 422 (HipCalculationFailuresResponse)
{
  "failures": [
    { "reason": "No Match for person details provided", "code": 63119 }
  ]
}

// Transformed GmpBulkCalculationResponse (globalErrorCode = first failure; no periods)
{
  "periods": [],
  "globalErrorCode": 63119,
  "containsErrors": true
}
```


Use service manager to run all the required services:
```
sm2 --start GMP_ALL
```
## Testing and Coverage

- Run tests and generate coverage:
  ```bash
  sbt clean coverage test coverageReport
  ```
- Open the HTML report:
  `target/scala-2.13/scoverage-report/index.html`

To run the application execute
```
sbt run
```

### License

This code is open source software licensed under the [Apache 2.0 License]("http://www.apache.org/licenses/LICENSE-2.0.html").
