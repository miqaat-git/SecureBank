package com.securebank.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.SpringBootVersion;
import org.springframework.context.annotation.Profile;
import org.springframework.core.SpringVersion;
import org.springframework.security.core.SpringSecurityCoreVersion;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Phase 0 spike artifact. It proves that a JSP using EL and JSTL renders under the approved
 * stack. It exists only under the dev and test profiles, is not a product route and is not part
 * of the blueprint route inventory (blueprint N4). It exposes no secrets and no business data.
 */
@Controller
@Profile({"dev", "test"})
public class Phase0ProofController {

    @GetMapping("/phase0/proof")
    public String proof(Model model, HttpServletRequest request) {
        model.addAttribute("pageTitle", "SecureBank Phase 0 compatibility proof");
        // The last item contains HTML characters on purpose: c:out must escape them.
        model.addAttribute("items", List.of("alpha", "beta", "gamma", "a&b <i>tag</i>"));

        Map<String, String> versions = new LinkedHashMap<>();
        versions.put("Java", System.getProperty("java.version"));
        versions.put("Spring Boot", SpringBootVersion.getVersion());
        versions.put("Spring Framework", SpringVersion.getVersion());
        versions.put("Spring Security", SpringSecurityCoreVersion.getVersion());
        versions.put("Servlet container", request.getServletContext().getServerInfo());
        versions.put("Servlet API", request.getServletContext().getMajorVersion()
                + "." + request.getServletContext().getMinorVersion());
        model.addAttribute("versions", versions);

        // Resolved to WEB-INF/views/phase0/proof.jsp by spring.mvc.view.prefix and suffix.
        return "phase0/proof";
    }
}
