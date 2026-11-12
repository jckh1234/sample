# C# state file processor

.NET Framework 4.8 / C# 7.3 sample. Add `StateFileProcessor.cs` to a net48 project. It uses `Directory.GetFiles`, `File.ReadAllText`, and `DateTime.TryParseExact`.

## Call

```csharp
DateTime lastReadDate = LoadLastReadDate(); // caller-persisted timestamp (date and time)
DateTime lastProcessed = StateFileProcessor.ProcessStateFiles(@"C:\data\state", lastReadDate);
SaveLastReadDate(lastProcessed); // persist the full timestamp before the next run
```

`ProcessStateFiles` returns a full `DateTime`, including time of day. Persist that value and pass it back in as `lastReadDate`. On the first run, pass a timestamp strictly before the first file you want to read.

A round-trip such as `yyyy-MM-dd HH:mm:ss` keeps the time. A date-only value would drop the time and select files from that calendar day again.

## Filename pattern

Files live directly in `folderPath` (the folder is not searched recursively):

```
stateYYMMDDHHmmss.txt
```

The 12 digits are a timestamp: two-digit year, month, day, hour, minute, and second. The time differs on every file, so the processor lists the folder and reads the timestamp from each name. The two-digit year is read as 2000-2099.

Examples:

- `state261112063145.txt` is 12 November 2026 at 06:31:45
- `state261111051123.txt` is 11 November 2026 at 05:11:23

Names that do not match `state` + 12 digits + `.txt` are ignored. A 12-digit stamp that is not a real timestamp (for example month 99) is ignored as well.

## What it does

The method lists `folderPath` and keeps matching files whose timestamp is strictly after `lastReadDate`. Files from today and later days are included.

- Matching files are processed oldest first.
- For each selected file, it reads the text and calls `saveToDb(fileName, contents, fileDate)`. `fileDate` is the timestamp parsed from the file name.
- It returns the timestamp of the last file successfully passed to `saveToDb`.
- If no matching file is strictly after `lastReadDate`, it returns `lastReadDate` unchanged.

A day with no file, such as a holiday, needs no special case. That day simply does not appear in the listing. A gap does not move the returned timestamp by itself. When a later file is processed in the same run, the returned timestamp is that later file, so older files are not selected again once the caller persists it.

`saveToDb` is a stub. Replace its body before relying on the returned timestamp. An empty stub still counts as a successful save, and that timestamp will not be read again once it is persisted.

`lastReadDate` is not stored inside this class. The caller must persist the returned value so the next run does not reprocess the same file.
