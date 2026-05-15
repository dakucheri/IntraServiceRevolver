package org.example;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;

class ConfigLoader {
    private static final Properties props = new Properties();

    static {
        try {
            props.load(ConfigLoader.class.getClassLoader().getResourceAsStream("config.properties"));
        } catch (IOException e) {
            throw new RuntimeException("Ошибка загрузки конфигурационного файла", e);
        }
    }

    public static String getAuth() {
        return props.getProperty("app.auth");
    }

    public static int getCheckIntervalMs() {
        return Integer.parseInt(props.getProperty("app.check.interval.ms"));
    }

    public static Map<String, String> getLoginToExecutorId() {
        Map<String, String> result = new HashMap<>();
        props.stringPropertyNames().forEach(key -> {
            if (key.startsWith("login.to.executor.id.")) {
                String login = key.replace("login.to.executor.id.", "");
                result.put(login, props.getProperty(key));
            }
        });
        return result;
    }
}

public class MainApp {
    private static final String AUTH = ConfigLoader.getAuth();
    private static final String BASE_URL = "https://test1232.intraservice.ru/api/task";
    private static final String ACTIVE_LINE_URL = "http://192.168.100.114/api/v1/line/active";
    private static final int CHECK_INTERVAL_MS = ConfigLoader.getCheckIntervalMs();
    private static final Set<Integer> PROCESSED_TASK_IDS = new HashSet<>();
    private static final AtomicBoolean IS_RUNNING = new AtomicBoolean(false);

    // Соответствие логинов и executorId

    private static final Map<String, String> LOGIN_TO_EXECUTOR_ID = ConfigLoader.getLoginToExecutorId();

//    static {
//        Map<String, String> tempMap = new HashMap<>();
//        tempMap.put("ikornilov", "319");
//        tempMap.put("employee-1", "329");
//        LOGIN_TO_EXECUTOR_ID = Collections.unmodifiableMap(tempMap);
//    }

    public static void main(String[] args) {
        Thread processingThread = new Thread(MainApp::processTasks);
        processingThread.setDaemon(true);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\nПолучен сигнал завершения. Останавливаем обработку...");
            IS_RUNNING.set(false);
        }));

        Scanner scanner = new Scanner(System.in);
        System.out.println("Приложение запущено. Доступные команды:");
        System.out.println("  start — запустить обработку задач");
        System.out.println("  stop — остановить обработку задач");
        System.out.println("  status — показать статус");
        System.out.println("  exit — выйти из приложения");

        while (true) {
            System.out.print("\nВведите команду: ");
            String command = scanner.nextLine().trim().toLowerCase();

            switch (command) {
                case "start":
                    if (!IS_RUNNING.get()) {
                        IS_RUNNING.set(true);
                        processingThread.start();
                        System.out.println("Обработка задач запущена.");
                    } else {
                        System.out.println("Обработка уже запущена.");
                    }
                    break;
                case "stop":
                    IS_RUNNING.set(false);
                    System.out.println("Остановка обработки...");
                    break;
                case "status":
                    System.out.println("Статус: " + (IS_RUNNING.get() ? "Обработка запущена" : "Обработка остановлена"));
                    System.out.println("Обработано задач: " + PROCESSED_TASK_IDS.size());
                    break;
                case "exit":
                    IS_RUNNING.set(false);
                    scanner.close();
                    System.exit(0);
                    break;
                default:
                    System.out.println("Неизвестная команда. Попробуйте: start, stop, status, exit");
                    break;
            }
        }
    }

    private static void processTasks() {
        HttpClient client = HttpClientBuilder.create().build();
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        while (IS_RUNNING.get()) {
            try {
                System.out.println("[INFO] Получение списка задач... " + new java.util.Date());
                HttpGet getRequest = new HttpGet(BASE_URL + "?StatusIds=31&fields=Id,Name,StatusId,Creator,ExecutorIds");
                getRequest.setHeader("Authorization", AUTH);

                HttpResponse response = client.execute(getRequest);
                String responseBody = EntityUtils.toString(response.getEntity(), "UTF-8");

                if (response.getStatusLine().getStatusCode() == 200) {
                    TaskList tasks = mapper.readValue(responseBody, TaskList.class);
                    System.out.println("[INFO] Найдено задач для обработки: " + tasks.getTasks().size());

                    // Получаем список сотрудников на смене
                    List<String> activeEmployees = getActiveEmployees(client, mapper);

                    for (Task task : tasks.getTasks()) {
                        if (!IS_RUNNING.get()) break;
                        processTask(client, task, activeEmployees);
                    }
                } else {
                    System.err.println("[ERROR] Ошибка HTTP: " + response.getStatusLine());
                }

                waitForNextCheck();
            } catch (IOException e) {
                System.err.println("[ERROR] Ошибка при получении данных: " + e.getMessage());
                waitForNextCheck();
            }
        }
        System.out.println("[INFO] Обработка остановлена.");
    }

    private static void waitForNextCheck() {
        try {
            Thread.sleep(CHECK_INTERVAL_MS);
        } catch (InterruptedException e) {
            // Восстанавливаем статус прерываниям
            Thread.currentThread().interrupt();
            System.out.println("[INFO] Ожидание прервано, продолжаем работу...");
        }
    }

    // Получаем список активных сотрудников
    private static List<String> getActiveEmployees(HttpClient client, ObjectMapper mapper) {
        try {
            HttpGet getRequest = new HttpGet(ACTIVE_LINE_URL);
            HttpResponse response = client.execute(getRequest);

            if (response.getStatusLine().getStatusCode() == 200) {
                String responseBody = EntityUtils.toString(response.getEntity(), "UTF-8");

                // Десериализуем напрямую в массив Employee[]
                Employee[] employees = mapper.readValue(responseBody, Employee[].class);

                List<String> result = new ArrayList<>();
                for (Employee employee : employees) {
                    String displayName = employee.getDisplayName();
                    if (LOGIN_TO_EXECUTOR_ID.containsKey(displayName)) {
                        result.add(displayName);
                    }
                }
                return result;
            }
        } catch (Exception e) {
            System.err.println("[ERROR] Ошибка получения списка активных сотрудников: " + e.getMessage());
        }
        return Collections.emptyList();
    }

    // Определяем сотрудника с наименьшим количеством задач
    private static String findLeastLoadedEmployee(HttpClient client, List<String> employees) {
        String selectedEmployee = null;
        int minCount = Integer.MAX_VALUE;

        for (String employeeLogin : employees) {
            String executorId = LOGIN_TO_EXECUTOR_ID.get(employeeLogin);
            try {
                HttpGet getRequest = new HttpGet(BASE_URL +
                        "?StatusIds=27&fields=Id&ExecutorIds=" + executorId);
                getRequest.setHeader("Authorization", AUTH);

                HttpResponse response = client.execute(getRequest);
                if (response.getStatusLine().getStatusCode() == 200) {
                    String responseBody = EntityUtils.toString(response.getEntity(), "UTF-8");
                    TaskList taskList = new ObjectMapper().readValue(responseBody, TaskList.class);
                    int count = taskList.getTasks().size();

                    if (count < minCount) {
                        minCount = count;
                        selectedEmployee = executorId;
                    } else if (count == minCount && selectedEmployee == null) {
                        // Случайный выбор при равном количестве задач
                        selectedEmployee = new Random().nextBoolean() ? executorId : selectedEmployee;
                    }
                }
            } catch (Exception e) {
                System.err.println("[ERROR] Ошибка проверки нагрузки сотрудника " + employeeLogin + ": " + e.getMessage());
            }
        }
        return selectedEmployee;
    }

    private static void processTask(HttpClient client, Task task, List<String> activeEmployees) {
        String executorIds = task.getExecutorIds();

        if (executorIds == null || executorIds.isEmpty()) {
            // Находим наименее загруженного сотрудника
            String targetExecutorId = findLeastLoadedEmployee(client, activeEmployees);

            if (targetExecutorId != null) {
                try {
                    updateTask(client, task.getId(), targetExecutorId);
                    PROCESSED_TASK_IDS.add(task.getId());
                    System.out.println("[SUCCESS] Задача №" + task.getId() +
                            " назначена на сотрудника с ID " + targetExecutorId);
                } catch (Exception e) {
                    System.err.println("[ERROR] Ошибка обработки задачи №" + task.getId() +
                            ": " + e.getMessage());
                }
            } else {
                System.out.println("[WARNING] Не удалось найти подходящего сотрудника для задачи №" +
                        task.getId() + ". Пропускаем.");
            }
        } else {
            System.out.println("[SKIPPED] Задача №" + task.getId() +
                    " уже имеет исполнителя");
        }
    }

    private static void updateTask(HttpClient client, int taskId, String executorId) throws IOException {
        String url = BASE_URL + "/" + taskId;
        String body = "{\n" +
                "\"ExecutorIds\": \"" + executorId + "\",\n" +
                "\"StatusId\": \"27\",\n" +
                "\"Comment\": \"Заявка в работе\",\n" +
                "\"IsPrivateComment\": false\n" +
                "}";

        HttpPut putRequest = new HttpPut(url);
        putRequest.setHeader("Authorization", AUTH);
        putRequest.setHeader("Content-Type", "application/json");
        putRequest.setEntity(new StringEntity(body, "UTF-8"));

        HttpResponse response = client.execute(putRequest);
        if (response.getStatusLine().getStatusCode() != 200) {
            throw new IOException("HTTP error: " + response.getStatusLine());
        }
    }

//    @JsonIgnoreProperties(ignoreUnknown = true)
//    public static class ActiveLineResponse {
//        @JsonProperty("employees")
//        private List<Employee> employees;
//
//        public List<Employee> getEmployees() {
//            return employees != null ? employees : new ArrayList<>();
//        }
//
//        public void setEmployees(List<Employee> employees) {
//            this.employees = employees;
//        }
//    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Employee {
        @JsonProperty("display_name")
        private String displayName;

        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Task {
        @JsonProperty("Id")
        private int Id;
        @JsonProperty("Name")
        private String Name;
        @JsonProperty("StatusId")
        private int StatusId;
        @JsonProperty("Creator")
        private String Creator;
        @JsonProperty("ExecutorIds")
        private String ExecutorIds;
        @JsonProperty("Comment")
        private String Comment;

        public int getId() {
            return Id;
        }

        public String getName() {
            return Name;
        }

        public int getStatusId() {
            return StatusId;
        }

        public String getCreator() {
            return Creator;
        }

        public String getExecutorIds() {
            return ExecutorIds;
        }

        public String getComment() {
            return Comment;
        }

        public void setId(int id) {
            this.Id = id;
        }

        public void setName(String name) {
            this.Name = name;
        }

        public void setStatusId(int statusId) {
            this.StatusId = statusId;
        }

        public void setCreator(String creator) {
            this.Creator = creator;
        }

        public void setExecutorIds(String executorIds) {
            this.ExecutorIds = executorIds;
        }

        public void setComment(String comment) {
            this.Comment = comment;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TaskList {
        @JsonProperty("Tasks")
        private List<Task> tasks;
        @JsonProperty("Priorities")
        private List<Object> priorities;
        @JsonProperty("Services")
        private List<Object> services;

        public List<Task> getTasks() {
            return tasks != null ? tasks : new ArrayList<>();
        }

        public void setTasks(List<Task> tasks) {
            this.tasks = tasks;
        }

        public List<Object> getPriorities() {
            return priorities != null ? priorities : new ArrayList<>();
        }

        public void setPriorities(List<Object> priorities) {
            this.priorities = priorities;
        }

        public List<Object> getServices() {
            return services != null ? services : new ArrayList<>();
        }

        public void setServices(List<Object> services) {
            this.services = services;
        }
    }
}