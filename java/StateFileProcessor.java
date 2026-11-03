import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Reads stateYYMMDD.txt files from the day after {@code lastReadDate} through yesterday.
 * The caller persists the returned date and supplies it on the next run.
 */
public final class StateFileProcessor {
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyMMdd");

    private StateFileProcessor() {
    }

    public static LocalDate processStateFiles(String folderPath, LocalDate lastReadDate) throws IOException {
        LocalDate endDate = LocalDate.now().minusDays(1);
        LocalDate cursor = lastReadDate.plusDays(1);
        LocalDate lastProcessed = lastReadDate;

        while (!cursor.isAfter(endDate)) {
            String fileName = "state" + cursor.format(FILE_DATE) + ".txt";
            Path path = Paths.get(folderPath, fileName);

            if (Files.exists(path)) {
                String contents = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
                saveToDb(fileName, contents, cursor);
                lastProcessed = cursor;
            }

            cursor = cursor.plusDays(1);
        }

        return lastProcessed;
    }

    // Stub only. Replace this body with the real database write.
    private static void saveToDb(String fileName, String contents, LocalDate fileDate) {
    }
}
