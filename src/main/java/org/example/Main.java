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
    private final static String pathToReceiptsFolder = "/home/yuri/samurai/wemove/migration/osn-photos";
    private final static String mongoConnectionURI = "mongodb://wemove:wemove@localhost:27117/wemove?authsource=admin";
    private final static String authToken = "Bearer eyJhbGciOiJIUzUxMiJ9.eyJzdWIiOiJhZG1pbkB3ZW1vdmUubG9jYWwiLCJpZCI6IjY4NzUxNTUyZDIxY2ZhMDFjMWZjMDQyMSIsInJvbGUiOiJBRE1JTiIsImlhdCI6MTc4ODc3NDk5NCwiZXhwIjoxNzg4OTA0NTk0fQ.dh-bj96_w79e9MU8jfg2_xecbk7hH83yZMBXr4Qj_zW8Mju01UgVZUE8oO85sGfHNj3TRTTgKSNY77y_fHo7pw\n";
    private final static String migrationLogPath = "/home/yuri/samurai/wemove/migration/migration_log.txt";

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
                        System.out.println("IO exception");
                    }

                }
        );

    }


    private static String[] getFileNames() {
        File receiptsFolder = new File(pathToReceiptsFolder);
        File[] receiptFiles = receiptsFolder.listFiles();
        String[] files = Arrays.stream(receiptFiles)
                .map(f -> f.getName())
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

            Map<String, String> pairs = new HashMap<>();
            long deliveriesWithReceiptCount = 0;
            long deliveriesWithoutReceiptCount = 0;
            Set<String> matchedNames = new HashSet<>();
            ArrayList<String> withReceipt = new ArrayList<>();
            HashMap<String, String> withoutReceiptUrls = new HashMap<String, String>();

            for (Document d : matchedDocuments) {
                String deliveryCode = d.getString("deliveryCode");
                String deliveryId = d.get("_id").toString();
                matchedNames.add(deliveryCode);
                String receiptUrl = d.getString("receiptUrl");
                if (receiptUrl != null && !receiptUrl.trim().isEmpty()) {
                    deliveriesWithReceiptCount++;
                    pairs.put(deliveryCode, receiptUrl);
                    withReceipt.add(deliveryCode);
                } else {
                    deliveriesWithoutReceiptCount++;
                    pairs.put(deliveryCode, "NO_URL");
                    withoutReceiptUrls.put(deliveryId, deliveryCode);
                }
            }

            ArrayList<String> notFoundInDb = new ArrayList<>();
            for (String fileName : fileNames) {
                if (!matchedNames.contains(fileName)) {
                    notFoundInDb.add(fileName);
                }
            }

//            System.out.println("\n--- Execution Summary ---");
//            System.out.println("FileNames count: " + fileNames.length);
//            System.out.println("Found matching deliveries: " + matchedDocuments.size());
//            System.out.println("Deliveries with receipt: " + deliveriesWithReceiptCount);
//            System.out.println("Deliveries without receipt: " + deliveriesWithoutReceiptCount);
//            System.out.println("wthout receipt array size" + withoutReceiptUrls.size());
//            System.out.println("Not found in db count: " + notFoundInDb.size());
            return withoutReceiptUrls;
        }
    }
}
