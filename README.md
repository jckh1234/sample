# Work samples

Small daily file-processing samples, one folder per language. Each one reads text files named `stateYYMMDD.txt` from a folder, catches up from the day after `lastReadDate` through yesterday, and skips dates that have no file.

`YYMMDD` is the file date. Example: `state261102.txt` is 2 November 2026.

For each file that exists, the sample reads the text and calls `saveToDb`. That method is a stub. Database access stays with the caller.

The returned date is the last date whose file was saved. If a date in the range has no file, it is skipped and does not move that date on its own. The caller must persist the returned date and pass it back in as `lastReadDate` on the next run.

- [C#](csharp/README.md) — .NET Framework 4.8, C# 7.3
- [Java](java/README.md) — Java 8
- [JavaScript](javascript/README.md) — Node.js

## External API sample

[aws-external-api](aws-external-api/README.md) is a separate AWS sample: an OpenAPI file imported by API Gateway, an Okta REQUEST authorizer Lambda, a proxy Lambda, and HashiCorp Vault for the Okta client secret. It is not part of the state-file processors above. Explanations in that README are in Traditional Chinese.
