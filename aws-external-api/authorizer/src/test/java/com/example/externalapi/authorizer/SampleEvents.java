package com.example.externalapi.authorizer;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads {@code events/*.json} from the test classpath (Maven copies {@code ../events})
 * or from the working directory Eclipse uses for Run As → JUnit Test.
 */
final class SampleEvents {
    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private SampleEvents() {
    }

    static <T> T load(String fileName, Class<T> type) throws IOException {
        try (InputStream in = open(fileName)) {
            return JSON.readValue(in, type);
        }
    }

    private static InputStream open(String fileName) throws IOException {
        InputStream classpath = SampleEvents.class.getClassLoader().getResourceAsStream("events/" + fileName);
        if (classpath != null) {
            return classpath;
        }
        Path[] candidates = new Path[] {
                Path.of("events").resolve(fileName),
                Path.of("..", "events").resolve(fileName),
                Path.of("aws-external-api", "events").resolve(fileName)
        };
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return Files.newInputStream(candidate);
            }
        }
        throw new IOException("Cannot find events/" + fileName
                + ". Working directory is " + Path.of("").toAbsolutePath());
    }
}
