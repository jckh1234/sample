# C# state file processor

.NET Framework 4.8 / C# 7.3 sample. Add `StateFileProcessor.cs` to a net48 project. It uses `Path.Combine`, `File.Exists`, and `File.ReadAllText` only.

## Call

```csharp
DateTime lastReadDate = LoadLastReadDate(); // caller-persisted calendar date
DateTime lastProcessed = StateFileProcessor.ProcessStateFiles(@"C:\data\state", lastReadDate);
SaveLastReadDate(lastProcessed); // persist before the next run
```

`ProcessStateFiles` returns a date at midnight. Persist that date (not a time of day). On the first run, pass the day before the first file you want to read.

## Filename pattern

Files live directly in `folderPath`:

```
stateYYMMDD.txt
```

`YYMMDD` is the file date. `state261102.txt` is 2 November 2026.

## What it does

The method is meant to be called daily. It walks from the day after `lastReadDate` through yesterday (the previous calendar day).

- If `stateYYMMDD.txt` exists, it reads the text and calls `saveToDb(fileName, contents, fileDate)`.
- If the file is missing (for example a holiday), it skips that date and continues.
- It returns the last date that was passed to `saveToDb`.
- If no file in the range exists, it returns `lastReadDate` with the time cleared.

A missing day does not move the returned date by itself. A later file in the same run still does, so a gap before that later file is not scanned again. Days after the last file found are tried again on the next run.

`saveToDb` is a stub. Replace its body before relying on the returned date. An empty stub still counts as a successful save, and that date will not be read again once a later bookmark is persisted.

`lastReadDate` is not stored inside this class. The caller must persist the returned value.
