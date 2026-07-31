package com.dayliane.home;

import com.dayliane.auth.AuthService;
import com.dayliane.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/home")
public class HomeController {
    private final HomeService homeService;
    private final AuthService authService;

    public HomeController(HomeService homeService, AuthService authService) {
        this.homeService = homeService;
        this.authService = authService;
    }

    @GetMapping("/today")
    public ApiResponse<Map<String, Object>> today(HttpServletRequest request) {
        long userId = authService.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(homeService.today(userId));
    }

    @GetMapping("/upcoming")
    public ApiResponse<Map<String, Object>> upcoming(HttpServletRequest request) {
        long userId = authService.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(homeService.upcoming(userId));
    }
}
