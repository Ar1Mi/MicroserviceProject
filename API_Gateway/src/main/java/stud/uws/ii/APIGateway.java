package stud.uws.ii;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

public class APIGateway {

    private static String gatewayHost;
    private static int gatewayPort;

    private static String registrationServiceHost;
    private static int registrationServicePort;

    private static String loginServiceHost;
    private static int loginServicePort;

    private static String postsServiceHost;
    private static int postsServicePort;

    private static String filesServiceHost;
    private static int filesServicePort;

    static {
        loadConfig();
    }

    public static void main(String[] args) {
        try (ServerSocket serverSocket = new ServerSocket(gatewayPort)) {
            System.out.println("API Gateway uruchomiono w porcie " + gatewayPort);

            while (true) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    new Thread(new ClientHandler(clientSocket)).start();
                } catch (IOException e) {
                    System.err.println("Błąd podczas przetwarzania żądania klienta: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("Nie udało się uruchomić API Gateway: " + e.getMessage());
        }
    }

    private static void loadConfig() {
        Properties properties = new Properties();
        try (InputStream input = new FileInputStream("config.properties")) {
            properties.load(input);
            gatewayHost = properties.getProperty("api.gateway.ip");
            gatewayPort = Integer.parseInt(properties.getProperty("api.gateway.port"));


            registrationServiceHost = properties.getProperty("registration.service.ip");
            registrationServicePort = Integer.parseInt(properties.getProperty("registration.service.port"));

            loginServiceHost = properties.getProperty("login.service.ip");
            loginServicePort = Integer.parseInt(properties.getProperty("login.service.port"));

            postsServiceHost = properties.getProperty("posts.service.ip");
            postsServicePort = Integer.parseInt(properties.getProperty("posts.service.port"));

            filesServiceHost = properties.getProperty("files.service.ip");
            filesServicePort = Integer.parseInt(properties.getProperty("files.service.port"));
        } catch (IOException e) {
            System.err.println("Ошибка загрузки конфигурации: " + e.getMessage());
        }
    }

    private static class ClientHandler implements Runnable {
        private Socket clientSocket;

        public ClientHandler(Socket socket) {
            this.clientSocket = socket;
        }

        @Override
        public void run() {
            handleClientRequest(clientSocket);
        }

        private void handleClientRequest(Socket clientSocket) {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
                 PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true)) {

                // Czytanie żądania CLI
                String jsonRequest = in.readLine();
                ObjectMapper objectMapper = new ObjectMapper();
                Map<String, Object> request = objectMapper.readValue(jsonRequest, HashMap.class);

                // Określenie usługi docelowej
                String target = (String) request.get("target");
                Map<String, Object> response;

                switch (target) {
                    case "registration_service":
                        response = forwardRequestToService(request, registrationServiceHost, registrationServicePort);
                        break;
                    case "login_service":
                        response = forwardRequestToService(request, loginServiceHost, loginServicePort);
                        break;
                    case "posts_service":
                        response = forwardRequestToService(request, postsServiceHost, postsServicePort);
                        break;
                    case "files_service":
                        response = forwardRequestToService(request, filesServiceHost, filesServicePort);
                        break;
                    default:
                        response = new HashMap<>();
                        response.put("type", "response");
                        response.put("id", request.get("id"));
                        response.put("status", "error");
                        response.put("message", "Unknown target: " + target);
                }

                // Odesłanie odpowiedzi do klienta
                String jsonResponse = objectMapper.writeValueAsString(response);
                out.println(jsonResponse);

            } catch (IOException e) {
                System.err.println("Błąd podczas przetwarzania żądania: " + e.getMessage());
            } finally {
                try {
                    clientSocket.close();
                } catch (IOException e) {
                    System.err.println("Błąd podczas zamykania połączenia: " + e.getMessage());
                }
            }
        }

        private Map<String, Object> forwardRequestToService(Map<String, Object> request, String host, int port) {
            try (Socket serviceSocket = new Socket(host, port);
                 PrintWriter out = new PrintWriter(serviceSocket.getOutputStream(), true);
                 BufferedReader in = new BufferedReader(new InputStreamReader(serviceSocket.getInputStream()))) {

                ObjectMapper objectMapper = new ObjectMapper();
                String jsonRequest = objectMapper.writeValueAsString(request);

                //Wysyłanie request do microserwisu
                out.println(jsonRequest);

                // Czekanie na odpowiedz
                String jsonResponse = in.readLine();
                return objectMapper.readValue(jsonResponse, HashMap.class);

            } catch (IOException e) {
                System.err.println("Błąd podczas uzyskiwania dostępu do usługi: " + e.getMessage());
                Map<String, Object> errorResponse = new HashMap<>();
                errorResponse.put("type", "response");
                errorResponse.put("id", request.get("id"));
                errorResponse.put("status", "error");
                errorResponse.put("message", "Failed to connect to the target service.");
                return errorResponse;
            }
        }
    }
}
