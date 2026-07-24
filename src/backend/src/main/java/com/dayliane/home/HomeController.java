package com.dayliane.home;

import com.dayliane.common.ApiResponse;
import com.dayliane.common.DbStore;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/home")
public class HomeController {
    private final DbStore store;

    public HomeController(DbStore store) {
        this.store = store;
    }

    @GetMapping("/today")
    public ApiResponse<Map<String, Object>> today(HttpServletRequest request) {
        long userId = store.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(store.homeToday(userId));
    }

    @GetMapping("/upcoming")
    public ApiResponse<Map<String, Object>> upcoming(HttpServletRequest request) {
        long userId = store.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(store.homeUpcoming(userId));
    }
}
