package cn.petshop.booking.controller;

import cn.petshop.booking.service.AppointmentService;
import cn.petshop.common.context.MemberContext;
import cn.petshop.common.web.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/appointments")
@RequiredArgsConstructor
public class AppointmentController {

    private final AppointmentService appointmentService;

    @PostMapping
    public R<AppointmentService.ApptVO> create(@RequestBody AppointmentService.CreateCmd cmd) {
        return R.ok(appointmentService.create(MemberContext.require(), cmd));
    }

    @DeleteMapping("/{id}")
    public R<Void> cancel(@PathVariable Long id) {
        appointmentService.cancel(MemberContext.require(), id);
        return R.ok();
    }

    /** 顾客自助确认到店（仅预约当天可点；重复确认被 CAS 拦截）。 */
    @PostMapping("/{id}/confirm-arrival")
    public R<Void> confirmArrival(@PathVariable Long id) {
        appointmentService.confirmArrival(MemberContext.require(), id);
        return R.ok();
    }

    @GetMapping
    public R<List<AppointmentService.ApptVO>> list(@RequestParam(required = false) String status) {
        Long memberId = MemberContext.require();
        return R.ok(appointmentService.toVOList(appointmentService.listMine(memberId, status)));
    }
}
