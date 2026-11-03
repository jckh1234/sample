# Java state file processor

Java 8 sample. `StateFileProcessor.processStateFiles` is the same daily catch-up as the C# sample.

Compile from this folder:

```bash
javac --release 8 StateFileProcessor.java
```

## Call

```java
LocalDate lastReadDate = LocalDate.parse(loadLastReadDate()); // caller-persisted yyyy-MM-dd
LocalDate lastProcessed = StateFileProcessor.processStateFiles("/data/state", lastReadDate);
saveLastReadDate(lastProcessed.toString()); // persist before the next run
```

`processStateFiles` throws `IOException` if a file that exists cannot be read. On the first run, pass the day before the first file you want to read.

## Filename pattern

Files live directly in `folderPath`:

```
stateYYMMDD.txt
```

`YYMMDD` is the file date. `state261102.txt` is 2 November 2026. File text is read as UTF-8.

## What it does

The method is meant to be called daily. It walks from the day after `lastReadDate` through yesterday (the previous calendar day).

- If `stateYYMMDD.txt` exists, it reads the text and calls `saveToDb(fileName, contents, fileDate)`.
- If the file is missing (for example a holiday), it skips that date and continues.
- It returns the last date that was passed to `saveToDb`.
- If no file in the range exists, it returns `lastReadDate`.

A missing day does not move the returned date by itself. A later file in the same run still does, so a gap before that later file is not scanned again. Days after the last file found are tried again on the next run.

`saveToDb` is a stub. Replace its body before relying on the returned date. An empty stub still counts as a successful save, and that date will not be read again once a later bookmark is persisted.

`lastReadDate` is not stored inside this class. The caller must persist the returned value.
