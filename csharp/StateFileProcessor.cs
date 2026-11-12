using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;

/// <summary>
/// Reads stateYYMMDDHHmmss.txt files whose timestamp is strictly after <c>lastReadDate</c>.
/// The caller persists the returned timestamp and supplies it on the next run.
/// </summary>
public static class StateFileProcessor
{
    private const string FilePrefix = "state";
    private const string FileSuffix = ".txt";
    private const string TimestampFormat = "yyMMddHHmmss";
    private static readonly CultureInfo FileNameCulture = CreateFileNameCulture();

    public static DateTime ProcessStateFiles(string folderPath, DateTime lastReadDate)
    {
        string[] paths = Directory.GetFiles(folderPath);
        List<StateFile> pending = new List<StateFile>();

        for (int i = 0; i < paths.Length; i++)
        {
            string fileName = Path.GetFileName(paths[i]);
            DateTime timestamp;
            if (!TryParseStateFileName(fileName, out timestamp))
            {
                continue;
            }

            if (timestamp > lastReadDate)
            {
                pending.Add(new StateFile(paths[i], fileName, timestamp));
            }
        }

        pending.Sort(CompareByTimestamp);

        DateTime lastProcessed = lastReadDate;
        for (int i = 0; i < pending.Count; i++)
        {
            StateFile file = pending[i];
            string contents = File.ReadAllText(file.FullPath);
            saveToDb(file.FileName, contents, file.Timestamp);
            lastProcessed = file.Timestamp;
        }

        return lastProcessed;
    }

    private static bool TryParseStateFileName(string fileName, out DateTime timestamp)
    {
        timestamp = default(DateTime);
        if (string.IsNullOrEmpty(fileName))
        {
            return false;
        }

        // state + yyMMddHHmmss + .txt
        if (fileName.Length != FilePrefix.Length + TimestampFormat.Length + FileSuffix.Length)
        {
            return false;
        }

        if (!fileName.StartsWith(FilePrefix, StringComparison.Ordinal))
        {
            return false;
        }

        if (!fileName.EndsWith(FileSuffix, StringComparison.Ordinal))
        {
            return false;
        }

        string stamp = fileName.Substring(FilePrefix.Length, TimestampFormat.Length);
        return DateTime.TryParseExact(
            stamp,
            TimestampFormat,
            FileNameCulture,
            DateTimeStyles.None,
            out timestamp);
    }

    private static int CompareByTimestamp(StateFile left, StateFile right)
    {
        int byTime = left.Timestamp.CompareTo(right.Timestamp);
        if (byTime != 0)
        {
            return byTime;
        }

        return string.Compare(left.FileName, right.FileName, StringComparison.Ordinal);
    }

    // yy is the year within 2000-2099. The invariant calendar would otherwise map 30-99 to 1930-1999.
    private static CultureInfo CreateFileNameCulture()
    {
        CultureInfo culture = (CultureInfo)CultureInfo.InvariantCulture.Clone();
        Calendar calendar = (Calendar)culture.Calendar.Clone();
        calendar.TwoDigitYearMax = 2099;
        culture.DateTimeFormat.Calendar = calendar;
        return culture;
    }

    private struct StateFile
    {
        public StateFile(string fullPath, string fileName, DateTime timestamp)
        {
            FullPath = fullPath;
            FileName = fileName;
            Timestamp = timestamp;
        }

        public string FullPath { get; private set; }
        public string FileName { get; private set; }
        public DateTime Timestamp { get; private set; }
    }

    // Stub only. Replace this body with the real database write.
    private static void saveToDb(string fileName, string contents, DateTime fileDate)
    {
    }
}
