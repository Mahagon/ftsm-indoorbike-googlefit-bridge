# Validate Google Health import

Use this procedure only for developer diagnosis. It reads exercise data from the authorized Google Health account and does not write or modify health data.

## 1. Verify the phone record

Open a completed workout in FTMS Bike Bridge and tap **Verify Health Connect**. A verified result confirms that Health Connect contains the expected biking session and the complete distance written by this package.

Google recommends its [Health Connect Toolbox](https://developer.android.com/health-and-fitness/health-connect/test/health-connect-toolbox) for independent record inspection.

## 2. Create a test OAuth client

Follow Google's [first API call codelab](https://developers.google.com/health/codelabs/make-your-first-api-call):

1. Create or select a Google Cloud project and enable **Google Health API**.
2. Configure an external OAuth consent screen in testing mode.
3. Add the Google account used by the Google Health app as a test user.
4. Create an OAuth web client with the codelab redirect URI.
5. Add only `https://www.googleapis.com/auth/googlehealth.activity_and_fitness.readonly`.
6. Complete the consent flow and exchange the authorization code for an access token.

Do not place the client secret, authorization code, access token, refresh token, or API responses in this repository.

## 3. Query imported exercises

In a temporary PowerShell session, set the access token and query one civil-date interval. The end is exclusive.

```powershell
$env:GOOGLE_HEALTH_ACCESS_TOKEN = "temporary-access-token"
.\tools\google-health-exercises.ps1 -Start "2026-08-21" -End "2026-08-22"
Remove-Item Env:GOOGLE_HEALTH_ACCESS_TOKEN
```

The script follows pagination and returns only records whose source is `HEALTH_CONNECT` and whose source package is `dev.frakw.ftmsbridge`. Compare the workout interval and `exercise.metricsSummary.distanceMillimeters` with the in-app verification result. The endpoint and supported filters are documented in the [Google Health exercise list reference](https://developers.google.com/health/reference/rest/v4/users.dataTypes.dataPoints/list).

## Interpret the result

- In-app verification fails: the bridge-to-Health-Connect write needs correction.
- Verification passes but the API returns no ride: Google Health did not import the Health Connect session.
- The API ride has no or incorrect `distanceMillimeters`: Google Health imported the session but lost its associated distance.
- The API ride contains the correct distance but the Today dashboard omits it: report a Google Health presentation/indexing issue with the two diagnostic results.

If import fails, disconnect and reconnect Health Connect in Google Health, confirm Exercise and Distance read access, and test with a newly recorded ride. Historical records may not be re-imported merely by reconnecting.
