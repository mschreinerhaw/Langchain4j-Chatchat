package com.chatchat.mcpserver.admin;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class AdminPageController {

    @GetMapping({"/", "/admin", "/admin/"})
    public String admin() {
        return "redirect:/admin/index.html";
    }
}
