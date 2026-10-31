package ru.mirea.delivery.http;

import java.util.List;
import java.util.regex.Pattern;

/** Маршрут API: метод, шаблон пути, доступ, роли, обработчик и код успешного ответа. */
public record Route(String method, String path, Pattern pattern, Access access, List<String> roles,
                    RouteHandler handler, int status) {

    public static Route of(String method, String path, Access access, List<String> roles, RouteHandler handler,
                           int status) {
        Pattern pattern = Pattern.compile(path.replaceAll("\\{(\\w+)}", "(?<$1>[^/]+)"));
        return new Route(method, path, pattern, access, roles, handler, status);
    }
}
