package stud.uws.ii;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.*;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.util.*;

public class CLI {

    private static String host;
    private static int port;
    private static final String REGISTRATION_SERVICE = "registration_service";
    private static final String LOGIN_SERVICE = "login_service";
    private static final String POSTS_SERVICE = "posts_service";
    private static final String FILES_SERVICE = "files_service";
    private static Scanner scanner;

    private static boolean isLoggedIn = false;
    private static String loggedInUsername;
    private static String loggedInPassword;

    private static String downloadDirectory;

    private static final int CHUNK_SIZE = 1024; // rozmiar pliku w byte

    static {
        loadConfig();
    }

    public static void main(String[] args) {
        scanner = new Scanner(System.in);

        while (true) {
            System.out.println();
            if (isLoggedIn) System.out.print(loggedInUsername + " ");
            System.out.println("Wybierz akcję:");
            System.out.println("1 - Rejestracja");
            System.out.println("2 - Login");
            System.out.println("3 - Dodaj post");
            System.out.println("4 - Pokaż posty");
            System.out.println("5 - Pobierz plik");
            System.out.println("6 - Przeslij plik");
            System.out.println("7 - Wyjscie");
            String choice = scanner.nextLine();

            switch (choice) {
                case "1":
                    handleRegistration();
                    break;
                case "2":
                    handleLogin();
                    break;
                case "3":
                    handleAddPost();
                    break;
                case "4":
                    handleShowAllPosts();
                    break;
                case "5":
                    handleDownloadFile();
                    break;
                case "6":
                    handleUploadFile();
                    break;
                case "7":
                    System.out.println("zamknięcie.");
                    return;
                default:
                    System.out.println("Zły wybór. Proszę powtórzyć.");
            }
        }
    }

    private static void handleRegistration() {
        System.out.print("Wpisz swoją nazwę użytkownika: ");
        String username = scanner.nextLine();

        System.out.print("Wpisz hasło: ");
        String password = scanner.nextLine();

        Map<String, Object> message = createMessage("request", REGISTRATION_SERVICE, Map.of("username", username, "password", password));

        sendRequest(message);
    }

    private static void handleLogin() {
        System.out.print("Wpisz swoją nazwę użytkownika: ");
        String username = scanner.nextLine();

        System.out.print("Wpisz hasło: ");
        String password = scanner.nextLine();

        Map<String, Object> message = createMessage("request", LOGIN_SERVICE, Map.of("username", username, "password", password));

        String jsonResponce = sendRequest(message);
        ObjectMapper objectMapper = new ObjectMapper();
        try {
            Map<String, Object> responce = objectMapper.readValue(jsonResponce, HashMap.class);
            String status = (String) responce.get("status");
            if (status.equals("success")) {
                isLoggedIn = true;
                loggedInUsername = username;
                loggedInPassword = password;
            } else {
                isLoggedIn = false;
                loggedInUsername = null;
                loggedInPassword = null;
            }
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private static void handleAddPost() {
        if (isLoggedIn) {
            System.out.print("Wpisz tytuł posta: ");
            String title = scanner.nextLine();

            System.out.print("Wpisz treść posta: ");
            String content = scanner.nextLine();

            Map<String, Object> postRequest = new HashMap<>();
            postRequest.put("type", "request");
            postRequest.put("id", UUID.randomUUID().toString());
            postRequest.put("action", "add_post");
            postRequest.put("target", "posts_service");

            Map<String, String> contentData = new HashMap<>();
            contentData.put("username", loggedInUsername);
            contentData.put("password", loggedInPassword);
            contentData.put("title", title);
            contentData.put("content", content);
            contentData.put("created_at", LocalDateTime.now().toString());

            postRequest.put("content", contentData);

            sendRequest(postRequest);
        } else {
            System.out.println("Nie masz uprawnień. Zaloguj się, aby dodać post.");
        }
    }

    private static void handleShowAllPosts() {
        if (isLoggedIn) {
            Map<String, Object> getPostsRequest = new HashMap<>();
            getPostsRequest.put("type", "request");
            getPostsRequest.put("id", UUID.randomUUID().toString());
            getPostsRequest.put("action", "get_all_posts");
            getPostsRequest.put("target", "posts_service");

            Map<String, String> contentData = new HashMap<>();
            contentData.put("username", loggedInUsername);
            contentData.put("password", loggedInPassword);

            getPostsRequest.put("content", contentData);

            String jsonResponse = sendRequest(getPostsRequest);

            try {
                ObjectMapper objectMapper = new ObjectMapper();
                Map<String, Object> response = objectMapper.readValue(jsonResponse, HashMap.class);

                if ("success".equals(response.get("status"))) {
                    List<Map<String, Object>> posts = (List<Map<String, Object>>) response.get("posts");
                    if (posts.isEmpty()) {
                        System.out.println("Brak dostępnych postów.");
                    } else {
                        System.out.println("Lista postów:");
                        for (Map<String, Object> post : posts) {
                            System.out.println("ID: " + post.get("id"));
                            System.out.println("Tytuł: " + post.get("title"));
                            System.out.println("Zawartość: " + post.get("content"));
                            System.out.println("Utworzono: " + post.get("created_at") + "  przez " + post.get("user_name"));
                            System.out.println("------");
                        }
                    }
                } else {
                    System.out.println("Bład: " + response.get("message"));
                }

            } catch (IOException e) {
                System.out.println("Błąd przetwarzania odpowiedzi: " + e.getMessage());
            }

        } else {
            System.out.println("Nie masz uprawnień. Zaloguj się, aby zobaczyć posty.");
        }
    }


    private static void handleUploadFile() {
        if (isLoggedIn) {
            System.out.print("Wprowadź nazwę pliku: ");
            String fileName = scanner.nextLine();

            System.out.print("Wprowadź ścieżkę pliku: ");
            String filePath = scanner.nextLine();

            try {
                byte[] fileBytes = Files.readAllBytes(Paths.get(filePath));
                int totalChunks = (int) Math.ceil((double) fileBytes.length / CHUNK_SIZE);

                for (int i = 0; i < totalChunks; i++) {
                    int start = i * CHUNK_SIZE;
                    int end = Math.min(start + CHUNK_SIZE, fileBytes.length);
                    byte[] chunk = Arrays.copyOfRange(fileBytes, start, end);
                    String fileData = Base64.getEncoder().encodeToString(chunk);

                    Map<String, Object> fileRequest = new HashMap<>();
                    fileRequest.put("type", "request");
                    fileRequest.put("id", UUID.randomUUID().toString());
                    fileRequest.put("action", "upload_file_chunk");
                    fileRequest.put("target", "files_service");

                    Map<String, String> contentData = new HashMap<>();
                    contentData.put("username", loggedInUsername);
                    contentData.put("password", loggedInPassword);
                    contentData.put("file_name", fileName);
                    contentData.put("file_data", fileData);
                    contentData.put("chunk_index", String.valueOf(i));
                    contentData.put("total_chunks", String.valueOf(totalChunks));

                    fileRequest.put("content", contentData);
                    sendRequest(fileRequest);
                }

            } catch (IOException e) {
                System.out.println("Błąd odczytu pliku: " + e.getMessage());
            }
        } else {
            System.out.println("Nie jesteś autoryzowany. Wprowadź plik do pobrania.");
        }
    }

    private static void handleDownloadFile() {
        if (isLoggedIn) {
            System.out.print("Wprowadź nazwę pliku pobierania: ");
            String fileName = scanner.nextLine();

            Map<String, Object> downloadRequest = new HashMap<>();
            downloadRequest.put("type", "request");
            downloadRequest.put("id", UUID.randomUUID().toString());
            downloadRequest.put("action", "download_file");
            downloadRequest.put("target", "files_service");

            Map<String, String> contentData = new HashMap<>();
            contentData.put("username", loggedInUsername);
            contentData.put("password", loggedInPassword);
            contentData.put("file_name", fileName);

            downloadRequest.put("content", contentData);

            // Wysyłanie żądania i czekanie na dane na części
            try {
                String savePath = downloadDirectory + File.separator + fileName;
                FileOutputStream fileOutputStream = new FileOutputStream(savePath);
                int packetNumber = 1;
                boolean isLastPacket = false;

                while (!isLastPacket) {
                    // Aktualizujemy treść żądania bieżącego pakietu
                    contentData.put("packet_number", String.valueOf(packetNumber));
                    String jsonResponse = sendRequest(downloadRequest);

                    ObjectMapper objectMapper = new ObjectMapper();
                    Map<String, Object> response = objectMapper.readValue(jsonResponse, HashMap.class);

                    if ("success".equals(response.get("status"))) {
                        String fileData = (String) response.get("file_data");
                        byte[] decodedBytes = Base64.getDecoder().decode(fileData);

                        fileOutputStream.write(decodedBytes);

                        // Sprawdzenie czy ten pakiet jest ostatnim
                        isLastPacket = Boolean.parseBoolean((String) response.get("is_last_packet"));
                        packetNumber++;
                    } else {
                        System.out.println("Blad: " + response.get("message"));
                        return;
                    }
                }

                fileOutputStream.close();
                System.out.println("Plik jest pomyślnie pobrany.");

            } catch (IOException e) {
                System.out.println("Błąd ładowania pliku: " + e.getMessage());
            }
        } else {
            System.out.println("Musisz zalogowac się, aby pobrać plik.");
        }
    }

    private static Map<String, Object> createMessage(String type, String target, Map<String, String> content) {
        Map<String, Object> message = new HashMap<>();
        message.put("type", type);
        message.put("id", UUID.randomUUID().toString());
        message.put("target", target);
        message.put("date", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ").format(new Date()));
        message.put("content", content);
        return message;
    }

    private static String sendRequest(Map<String, Object> message) {
        try (Socket socket = new Socket(host, port);
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {

            ObjectMapper objectMapper = new ObjectMapper();
            String jsonData = objectMapper.writeValueAsString(message);

            System.err.println("Request: " + jsonData);

            out.println(jsonData);

            String response = in.readLine();
            System.err.println("Responce: " + response);
            return response;

        } catch (IOException e) {
            System.err.println("Błąd połączenia: " + e.getMessage());
        }
        return "";
    }

    private static void loadConfig() {
        Properties properties = new Properties();
        try (InputStream input = new FileInputStream("config.properties")) {
            properties.load(input);
            host = properties.getProperty("api.gateway.ip");
            port = Integer.parseInt(properties.getProperty("api.gateway.port"));
            downloadDirectory = properties.getProperty("download.directory");
        } catch (IOException e) {
            System.err.println("Błąd ładowania konfiguracji: " + e.getMessage());
        }
    }
}
