package org.example;

import java.io.*;
import java.nio.file.*;
import java.util.*;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import org.apache.hc.client5.http.entity.mime.MultipartEntityBuilder;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.support.ClassicRequestBuilder;
import org.bson.Document;

public class Main {
    //initialize those values first
    private final static String pathToReceiptsFolder = null;
    private final static String mongoConnectionURI = null;
    private final static String authToken = null;
    private final static String migrationLogPath = null;

    public static void main(String[] args) throws IOException {
        String[] fileNames = getFileNames();
        Map<String, String> found = filterOnesToMigrate(fileNames);
        migrate(found);
        System.out.println("migration completed!!!");

    }

    private static void migrate(Map<String, String> filesToMigrate) {
        var httpClient = HttpClientBuilder.create().build();
        System.out.println("files to migrate count is : " + filesToMigrate.size());
        filesToMigrate.forEach(
                (deliveryId, deliveryCode) -> {
                    String path = pathToReceiptsFolder + "/%s.jpeg".formatted(deliveryCode);
//                    System.out.println("file path is : " + path);
                    HttpEntity entity = MultipartEntityBuilder
                            .create()
                            .addBinaryBody("file", new File(path))

                            .build();
                    String uri = "http://localhost:9090/api/deliveries/%s/receipt".formatted(deliveryId);
                    try (ClassicHttpResponse response = httpClient.execute(ClassicRequestBuilder.post(uri).setEntity(entity)
                            .setHeader("Authorization", authToken)
                            .build())) {
                        Path logFilePath = Path.of(migrationLogPath);
                        String content = "%s:%s\n".formatted(deliveryCode, response.getCode());
                        Files.writeString(
                                logFilePath,
                                content,
                                StandardOpenOption.APPEND
                        );
                    } catch (IOException e) {
                        Path logFilePath = Path.of(migrationLogPath);
                        String content = "failing delivery %s:%s\n".formatted(deliveryCode, e);
                        try {
                            Files.writeString(
                                    logFilePath,
                                    content,
                                    StandardOpenOption.APPEND
                            );
                        } catch (IOException ex) {
                            System.out.println("an error happended look into log file");
                        }
                    }

                }
        );

    }


    private static String[] getFileNames() {
        File receiptsFolder = new File(pathToReceiptsFolder);
        File[] receiptFiles = receiptsFolder.listFiles();
        if (receiptFiles == null) {
            throw new IllegalStateException("Receipts folder not found: " + pathToReceiptsFolder);
        }
        String[] files = Arrays.stream(receiptFiles)
                .map(f -> f.getName())
                .filter(name -> name.lastIndexOf('.') > 0)
                .map(name -> name.substring(0, name.lastIndexOf('.')))
                .toArray(String[]::new);
        return files;
    }

    private static Map<String, String> filterOnesToMigrate(String[] fileNames) throws IOException {

        try (MongoClient mongoClient = MongoClients.create(mongoConnectionURI)) {
            MongoDatabase database = mongoClient.getDatabase("wemove");
            MongoCollection<Document> collection = database.getCollection("deliveries");
            var filter = Filters.in("deliveryCode", fileNames);
            ArrayList<Document> matchedDocuments = collection.find(filter).into(new ArrayList<>());

            HashMap<String, String> withoutReceiptUrls = new HashMap<String, String>();

            for (Document d : matchedDocuments) {
                String deliveryCode = d.getString("deliveryCode");
                String deliveryId = d.get("_id").toString();
                String receiptUrl = d.getString("receiptUrl");
                if (receiptUrl == null || receiptUrl.trim().isEmpty()) {
                    withoutReceiptUrls.put(deliveryId, deliveryCode);
                }
            }

            return withoutReceiptUrls;
        }
    }
}
