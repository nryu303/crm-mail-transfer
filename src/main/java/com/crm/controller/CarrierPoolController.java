package com.crm.controller;

import com.crm.dto.CarrierPoolForm;
import com.crm.dto.CarrierPoolSearchForm;
import com.crm.dto.CsvImportResult;
import com.crm.entity.CarrierAddressPool;
import com.crm.service.CarrierPoolService;
import com.crm.service.CrmUserService;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import javax.validation.Valid;
import java.io.IOException;
import java.util.List;

@Controller
@RequestMapping("/manager/carrier-pool")
public class CarrierPoolController {

    private final CarrierPoolService service;
    private final com.crm.service.AdminAuthService adminAuthService;

    public CarrierPoolController(CarrierPoolService service,
                                  com.crm.service.AdminAuthService adminAuthService) {
        this.service = service;
        this.adminAuthService = adminAuthService;
    }

    @ModelAttribute("carrierCodes")
    public List<String> carrierCodes() { return CrmUserService.CARRIER_CODES; }

    @PostMapping("/bulk-delete")
    public String bulkDelete(@RequestParam(name = "ids", required = false) java.util.List<Long> ids,
                              @RequestParam(name = "confirmPassword", required = false) String confirmPassword,
                              javax.servlet.http.HttpSession session,
                              RedirectAttributes ra) {
        Long adminId = (Long) session.getAttribute(com.crm.interceptor.AuthInterceptor.SESSION_ADMIN_ID);
        if (!adminAuthService.verifyPassword(adminId, confirmPassword)) {
            ra.addFlashAttribute("flashError", "一括削除には管理者パスワードの確認が必要です");
            return "redirect:/manager/carrier-pool";
        }
        int n = service.deleteByIds(ids);
        ra.addFlashAttribute("flashSuccess", n + " 件のキャリアアドレスを削除しました");
        return "redirect:/manager/carrier-pool";
    }

    @GetMapping
    public String list(@ModelAttribute("searchForm") CarrierPoolSearchForm searchForm, Model model) {
        Page<CarrierAddressPool> pools = service.search(searchForm);
        model.addAttribute("pools", pools);
        model.addAttribute("unboundCount", service.countUnbound());
        java.util.Map<Long, Long> poolBindCounts = new java.util.HashMap<>();
        for (CarrierAddressPool p : pools.getContent()) {
            poolBindCounts.put(p.getId(), service.countBindingsForPool(p.getId()));
        }
        model.addAttribute("poolBindCounts", poolBindCounts);
        return "carrier/pool";
    }

    @GetMapping("/new")
    public String createForm(Model model) {
        model.addAttribute("form", new CarrierPoolForm());
        return "carrier/form";
    }

    @PostMapping
    public String create(@Valid @ModelAttribute("form") CarrierPoolForm form,
                         BindingResult br, RedirectAttributes ra) {
        if (form.getSmtpPassword() == null || form.getSmtpPassword().isEmpty()) {
            br.rejectValue("smtpPassword", "required", "SMTPパスワードを入力してください");
        }
        if (br.hasErrors()) {
            return "carrier/form";
        }
        try {
            service.create(form);
            ra.addFlashAttribute("flashSuccess", "キャリアアドレスを追加しました");
            return "redirect:/manager/carrier-pool";
        } catch (CarrierPoolService.DuplicateAddressException e) {
            br.rejectValue("address", "duplicate", "このアドレスは既に登録されています");
            return "carrier/form";
        }
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model, RedirectAttributes ra) {
        com.crm.entity.CarrierAddressPool p = service.findById(id).orElse(null);
        if (p == null) {
            ra.addFlashAttribute("flashError", "キャリアアドレスが見つかりません");
            return "redirect:/manager/carrier-pool";
        }
        CarrierPoolForm form = new CarrierPoolForm();
        form.setAddress(p.getAddress());
        form.setCarrierCode(p.getCarrierCode());
        form.setCarrierDomain(p.getCarrierDomain());
        form.setSmtpHost(p.getSmtpHost());
        form.setSmtpPort(p.getSmtpPort());
        form.setSmtpUsername(p.getSmtpUsername());
        // smtpPassword intentionally left blank — operator types only if changing it
        form.setIsActive(p.getIsActive());
        model.addAttribute("form", form);
        model.addAttribute("editId", id);
        return "carrier/form";
    }

    @PostMapping("/{id}")
    public String update(@PathVariable Long id,
                          @Valid @ModelAttribute("form") CarrierPoolForm form,
                          BindingResult br, RedirectAttributes ra, Model model) {
        if (br.hasErrors()) {
            model.addAttribute("editId", id);
            return "carrier/form";
        }
        try {
            service.update(id, form);
            ra.addFlashAttribute("flashSuccess", "キャリアアドレスを更新しました");
            return "redirect:/manager/carrier-pool";
        } catch (CarrierPoolService.DuplicateAddressException e) {
            br.rejectValue("address", "duplicate", "このアドレスは既に登録されています");
            model.addAttribute("editId", id);
            return "carrier/form";
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("flashError", e.getMessage());
            return "redirect:/manager/carrier-pool";
        }
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes ra) {
        service.delete(id);
        ra.addFlashAttribute("flashSuccess", "キャリアアドレスを削除しました");
        return "redirect:/manager/carrier-pool";
    }

    @GetMapping("/import")
    public String importForm() {
        return "carrier/import";
    }

    @PostMapping("/import")
    public String doImport(@RequestParam("file") MultipartFile file, Model model) throws IOException {
        if (file == null || file.isEmpty()) {
            model.addAttribute("flashError", "ファイルを選択してください");
            return "carrier/import";
        }
        CsvImportResult result = service.importCsv(file.getInputStream());
        model.addAttribute("result", result);
        return "carrier/import";
    }
}
