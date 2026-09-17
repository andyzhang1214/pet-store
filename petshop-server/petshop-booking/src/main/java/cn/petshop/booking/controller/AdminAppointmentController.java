package cn.petshop.booking.controller;

import cn.petshop.common.context.StoreContext;

import cn.petshop.booking.service.AppointmentService;
import cn.petshop.common.web.R;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 后台预约管理。/admin/** 由 petshop-admin 的 AdminTokenInterceptor 统一鉴权。
 */
@RestController
@RequestMapping("/admin/appointments")
@RequiredArgsConstructor
public class AdminAppointmentController {

    private final AppointmentService appointmentService;

    @GetMapping
    public R<List<AppointmentService.ApptVO>> list(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) String status) {
        return R.ok(appointmentService.toVOList(
                appointmentService.listByDate(StoreContext.DEFAULT_STORE_ID, date, status)));
    }

    @GetMapping("/{id}")
    public R<AppointmentService.ApptVO> detail(@PathVariable Long id) {
        return R.ok(appointmentService.toVO(appointmentService.loadOrThrow(id)));
    }

    @PostMapping("/{id}/noshow")
    public R<Void> noShow(@PathVariable Long id) {
        appointmentService.adminNoShow(id);
        return R.ok();
    }
}
