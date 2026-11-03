using System;
using System.IO;

/// <summary>
/// Reads stateYYMMDD.txt files from the day after <c>lastReadDate</c> through yesterday.
/// The caller persists the returned date and supplies it on the next run.
/// </summary>
public static class StateFileProcessor
{
    public static DateTime ProcessStateFiles(string folderPath, DateTime lastReadDate)
    {
        DateTime endDate = DateTime.Today.AddDays(-1);
        DateTime cursor = lastReadDate.Date.AddDays(1);
        DateTime lastProcessed = lastReadDate.Date;

        while (cursor <= endDate)
        {
            string fileName = "state" + cursor.ToString("yyMMdd") + ".txt";
            string path = Path.Combine(folderPath, fileName);

            if (File.Exists(path))
            {
                string contents = File.ReadAllText(path);
                saveToDb(fileName, contents, cursor);
                lastProcessed = cursor;
            }

            cursor = cursor.AddDays(1);
        }

        return lastProcessed;
    }

    // Stub only. Replace this body with the real database write.
    private static void saveToDb(string fileName, string contents, DateTime fileDate)
    {
    }
}
