package stud.uws.ii;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class FilesService {

    private static String dbUrl;
    private static String dbUsername;
    private static String dbPassword;
    private static int servicePort;
    private static String uploadDir;
    private static Map<String, FileUploadSession> uploadSessions = new ConcurrentHashMap<>();
    private static final int CHUNK_SIZE = 1024;

    static {
        loadConfig();
    }

    public static void main(String[] args) {
        try (ServerSocket serverSocket = new ServerSocket(servicePort)) {
            System.out.println("Files Service uruchomiono w porcie " + servicePort);

            while (true) {
                Socket clientSocket = serverSocket.accept();
                new Thread(() -> handleRequest(clientSocket)).start();
            }
        } catch (IOException e) {
            System.err.println("Nie udało się uruchomić Files Service: " + e.getMessage());
        }
    }

    private static class FileUploadSession {
        String filePath;
        int totalChunks;
        AtomicInteger receivedChunks = new AtomicInteger(0);

        FileUploadSession(String filePath, int totalChunks) {
            this.filePath = filePath;
            this.totalChunks = totalChunks;
        }
    }


    private static boolean writeFileChunk(FilesService.FileUploadSession session, String fileData, int chunkIndex) {
        try {
            byte[] decodedBytes = Base64.getDecoder().decode(fileData);
            try (RandomAccessFile raf = new RandomAccessFile(session.filePath, "rw")) {
                raf.seek((long) chunkIndex * CHUNK_SIZE);
                raf.write(decodedBytes);
                return true;
            }
        } catch (IOException e) {
            System.err.println("Błąd podczas pisania części pliku: " + e.getMessage());
            return false;
        }
    }

    private static void handleRequest(Socket clientSocket) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
             PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true)) {

            String jsonRequest = in.readLine();
            ObjectMapper objectMapper = new ObjectMapper();
            Map<String, Object> request = objectMapper.readValue(jsonRequest, HashMap.class);

            Map<String, Object> response;
            String action = (String) request.get("action");

            if ("upload_file_chunk".equals(action)) {
                Map<String, String> content = (Map<String, String>) request.get("content");
                String username = content.get("username");
                String password = content.get("password");
                String fileName = content.get("file_name");
                String fileData = content.get("file_data");
                int chunkIndex = Integer.parseInt(content.get("chunk_index"));
                int totalChunks = Integer.parseInt(content.get("total_chunks"));

                if (isUserLoggedIn(username, password)) {
                    String filePath = uploadDir + File.separator + fileName;
                    FileUploadSession session = uploadSessions.computeIfAbsent(fileName, k -> new FileUploadSession(filePath, totalChunks));

                    if (writeFileChunk(session, fileData, chunkIndex)) {
                        response = createResponse(request, "success", "Chunk " + (chunkIndex + 1) + " uploaded successfully.");

                        if (session.receivedChunks.incrementAndGet() == totalChunks) {
                            if (addFileRecord(username, fileName, filePath)) {
                                System.out.println("Plik jest całkowicie przesłany: " + fileName);
                                uploadSessions.remove(fileName); // Czyszczenie sesji po załadowaniu
                            } else {
                                System.out.println("Blad dodania pliku do bazy danych: " + fileName);
                            }
                        }
                    } else {
                        response = createResponse(request, "error", "Failed to upload chunk " + (chunkIndex + 1) + ".");
                    }
                } else {
                    response = createResponse(request, "error", "User not logged in.");
                }
            } else if ("download_file".equals(action)) {
                Map<String, String> content = (Map<String, String>) request.get("content");
                String username = content.get("username");
                String password = content.get("password");
                String fileName = content.get("file_name");
                int packetNumber = Integer.parseInt(content.get("packet_number"));

                if (isUserLoggedIn(username, password)) {
                    String filePath = getFileFromDatabase(username, fileName); //sprawdzanie właściciela i uzyskiwanie ścieżki

                    if (filePath != null) {
                        byte[] fileBytes = readFileInChunks(filePath, packetNumber); //odczytanie części pliku ze ścieżki

                        if (fileBytes != null) {
                            boolean isLastPacket = isLastPacket(filePath, packetNumber);
                            response = createResponse(request, "success", "File chunk sent successfully.");
                            response.put("file_data", Base64.getEncoder().encodeToString(fileBytes));
                            response.put("is_last_packet", String.valueOf(isLastPacket));
                        } else {
                            response = createResponse(request, "error", "Failed to read file chunk.");
                        }
                    } else {
                        response = createResponse(request, "error", "Access denied or file not found.");
                    }
                } else {
                    response = createResponse(request, "error", "User not logged in.");
                }
            } else {
                response = createResponse(request, "error", "Invalid action.");
            }

            String jsonResponse = objectMapper.writeValueAsString(response);
            out.println(jsonResponse);

        } catch (IOException e) {
            System.err.println("Błąd przetwarzania żądania: " + e.getMessage());
        }
    }

    private static byte[] readFileInChunks(String filePath, int packetNumber) {
        try {
            Path path = Paths.get(filePath);
            int chunkSize = 1024;
            int start = packetNumber * chunkSize;
            int end = Math.min((packetNumber + 1) * chunkSize, (int) Files.size(path));

            if (start >= end) {
                return null;
            }

            byte[] chunk = Arrays.copyOfRange(Files.readAllBytes(path), start, end);
            return chunk;

        } catch (IOException e) {
            System.err.println("Błąd odczytu bloku pliku: " + e.getMessage());
            return null;
        }
    }


    private static boolean isLastPacket(String filePath, int packetNumber) {
        try {
            long fileSize = Files.size(Paths.get(filePath));
            int chunkSize = 1024;
            int totalPackets = (int) Math.ceil((double) fileSize / chunkSize);
            return packetNumber >= totalPackets - 1;
        } catch (IOException e) {
            System.err.println("Błąd podczas sprawdzania ostatniego bloku: " + e.getMessage());
            return false;
        }
    }


    private static String getFileFromDatabase(String username, String fileName) {
        try (Connection connection = DriverManager.getConnection(dbUrl, dbUsername, dbPassword);
             PreparedStatement userStmt = connection.prepareStatement("SELECT id FROM users WHERE username = ?");
             PreparedStatement fileStmt = connection.prepareStatement("SELECT file_path FROM files WHERE user_id = ? AND file_name = ?")) {

            // Pobieranie user_id według nazwy użytkownika
            userStmt.setString(1, username);
            ResultSet userRs = userStmt.executeQuery();

            if (userRs.next()) {
                int userId = userRs.getInt("id");

                //sprawdzanie właściciela i uzyskiwanie ścieżki
                fileStmt.setInt(1, userId);
                fileStmt.setString(2, fileName);
                ResultSet fileRs = fileStmt.executeQuery();

                if (fileRs.next()) {
                    return fileRs.getString("file_path"); // Путь к файлу
                }
            }
        } catch (SQLException e) {
            System.err.println("Błąd podczas pobierania ścieżki pliku z bazy danych: " + e.getMessage());
        }
        return null; // Jeśli plik nie zostanie znaleziony lub użytkownik nie jest właścicielem
    }

    private static boolean isUserLoggedIn(String username, String password) {
        try (Connection connection = DriverManager.getConnection(dbUrl, dbUsername, dbPassword);
             PreparedStatement stmt = connection.prepareStatement("SELECT COUNT(*) FROM users WHERE username = ? AND password = ?")) {

            stmt.setString(1, username);
            stmt.setString(2, password);

            ResultSet rs = stmt.executeQuery();
            rs.next();
            return rs.getInt(1) > 0;

        } catch (SQLException e) {
            System.err.println("Błąd podczas pracy z bazą danych: " + e.getMessage());
            return false;
        }
    }

    private static boolean addFileRecord(String username, String fileName, String filePath) {
        try (Connection connection = DriverManager.getConnection(dbUrl, dbUsername, dbPassword);
             PreparedStatement userStmt = connection.prepareStatement("SELECT id FROM users WHERE username = ?");
             PreparedStatement fileStmt = connection.prepareStatement("INSERT INTO files (user_id, file_name, file_path) VALUES (?, ?, ?)")) {

            userStmt.setString(1, username);
            ResultSet rs = userStmt.executeQuery();
            if (rs.next()) {
                int userId = rs.getInt("id");

                fileStmt.setInt(1, userId);
                fileStmt.setString(2, fileName);
                fileStmt.setString(3, filePath);
                fileStmt.executeUpdate();
                return true;
            }

        } catch (SQLException e) {
            System.err.println("Błąd podczas dodawania wpisu pliku do bazy danych: " + e.getMessage());
        }
        return false;
    }

    private static Map<String, Object> createResponse(Map<String, Object> request, String status, String message) {
        Map<String, Object> response = new HashMap<>();
        response.put("type", "response");
        response.put("id", request.get("id"));
        response.put("status", status);
        response.put("message", message);
        return response;
    }

    private static void loadConfig() {
        Properties properties = new Properties();
        try (InputStream input = new FileInputStream("config.properties")) {
            properties.load(input);
            dbUrl = properties.getProperty("database.url");
            dbUsername = properties.getProperty("database.username");
            dbPassword = properties.getProperty("database.password");
            servicePort = Integer.parseInt(properties.getProperty("files.service.port"));
            uploadDir = properties.getProperty("upload.directory");
        } catch (IOException e) {
            System.err.println("Błąd ładowania konfiguracji: " + e.getMessage());
        }
    }
}
