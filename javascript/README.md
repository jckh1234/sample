# JavaScript state file processor

Node.js sample. `processStateFiles` is the same daily catch-up as the C# sample. It uses `fs` and `path`.

## Call

```javascript
const { processStateFiles } = require('./stateFileProcessor');

const lastReadDate = loadLastReadDate(); // 'YYYY-MM-DD' or a local Date
const lastProcessed = processStateFiles('/data/state', lastReadDate);
saveLastReadDate(formatDate(lastProcessed)); // persist before the next run

function formatDate(date) {
    const year = date.getFullYear();
    const month = String(date.getMonth() + 1).padStart(2, '0');
    const day = String(date.getDate()).padStart(2, '0');
    return year + '-' + month + '-' + day;
}
```

Pass a `YYYY-MM-DD` string or a local `Date` such as `new Date(2026, 10, 2)`. The return value is a `Date` at local midnight. Persist the calendar date. `toISOString()` is UTC and can shift the day.

On the first run, pass the day before the first file you want to read.

## Filename pattern

Files live directly in `folderPath`:

```
stateYYMMDD.txt
```

`YYMMDD` is the file date. `state261102.txt` is 2 November 2026. File text is read as UTF-8.

## What it does

The function is meant to be called daily. It walks from the day after `lastReadDate` through yesterday (the previous calendar day).

- If `stateYYMMDD.txt` exists, it reads the text and calls `saveToDb(fileName, contents, fileDate)`.
- If the file is missing (for example a holiday), it skips that date and continues.
- It returns the last date that was passed to `saveToDb`.
- If no file in the range exists, it returns `lastReadDate` at local midnight.

A missing day does not move the returned date by itself. A later file in the same run still does, so a gap before that later file is not scanned again. Days after the last file found are tried again on the next run.

`saveToDb` is a stub. Replace its body before relying on the returned date. An empty stub still counts as a successful save, and that date will not be read again once a later bookmark is persisted.

`lastReadDate` is not stored inside this module. The caller must persist the returned value.
