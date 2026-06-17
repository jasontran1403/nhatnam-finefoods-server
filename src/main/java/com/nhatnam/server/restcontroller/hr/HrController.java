package com.nhatnam.server.restcontroller.hr;

import com.nhatnam.server.dto.common.PageResponse;
import com.nhatnam.server.dto.hr.HrDtos.*;
import com.nhatnam.server.service.hr.HrService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/hr")
@RequiredArgsConstructor
public class HrController {

    private final HrService hrService;

    // ── Employee info ─────────────────────────────────────────────────────────

    @PutMapping("/employees/{userId}/info")
    public ResponseEntity<Void> updateEmployeeInfo(@PathVariable Long userId,
                                                    @RequestBody UpdateEmployeeInfoRequest req) {
        hrService.updateEmployeeInfo(userId, req);
        return ResponseEntity.ok().build();
    }

    // ── Salary ────────────────────────────────────────────────────────────────

    @PostMapping("/salaries")
    public ResponseEntity<SalaryDto> setSalary(@RequestBody SalaryRequest req) {
        return ResponseEntity.ok(hrService.setSalary(req));
    }

    @PostMapping("/salaries/batch")
    public ResponseEntity<List<SalaryDto>> batchSetSalary(@RequestBody BatchSalaryRequest req) {
        return ResponseEntity.ok(hrService.batchSetSalary(req));
    }

    @GetMapping("/salaries")
    public ResponseEntity<PageResponse<SalaryDto>> listSalaries(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        return ResponseEntity.ok(hrService.listSalaries(status, pageable));
    }

    @PutMapping("/salaries/{id}/approve")
    public ResponseEntity<SalaryDto> approveSalary(@PathVariable Long id) {
        return ResponseEntity.ok(hrService.approveSalary(id));
    }

    @PutMapping("/salaries/{id}/reject")
    public ResponseEntity<SalaryDto> rejectSalary(@PathVariable Long id,
                                                    @RequestBody RejectSalaryRequest req) {
        return ResponseEntity.ok(hrService.rejectSalary(id, req));
    }

    // ── Leave ─────────────────────────────────────────────────────────────────

    @PostMapping("/leaves")
    public ResponseEntity<LeaveRequestDto> createLeave(@RequestBody LeaveRequestCreate req) {
        return ResponseEntity.ok(hrService.createLeave(req));
    }

    @GetMapping("/leaves")
    public ResponseEntity<PageResponse<LeaveRequestDto>> listLeaves(
            @RequestParam(required = false) Long from,
            @RequestParam(required = false) Long to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        return ResponseEntity.ok(hrService.listLeaves(from, to, pageable));
    }

    @GetMapping("/leaves/{id}")
    public ResponseEntity<LeaveRequestDto> getLeave(@PathVariable Long id) {
        return ResponseEntity.ok(hrService.getLeave(id));
    }

    // ── Overtime ──────────────────────────────────────────────────────────────

    @PostMapping("/overtimes")
    public ResponseEntity<OvertimeRequestDto> createOvertime(@RequestBody OvertimeRequestCreate req) {
        return ResponseEntity.ok(hrService.createOvertime(req));
    }

    @GetMapping("/overtimes")
    public ResponseEntity<PageResponse<OvertimeRequestDto>> listOvertimes(
            @RequestParam(required = false) Long from,
            @RequestParam(required = false) Long to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        return ResponseEntity.ok(hrService.listOvertimes(from, to, pageable));
    }

    @GetMapping("/overtimes/{id}")
    public ResponseEntity<OvertimeRequestDto> getOvertime(@PathVariable Long id) {
        return ResponseEntity.ok(hrService.getOvertime(id));
    }

    // ── Payslip ───────────────────────────────────────────────────────────────

    @GetMapping("/payslip/{userId}")
    public ResponseEntity<PayslipDto> getPayslip(@PathVariable Long userId) {
        return ResponseEntity.ok(hrService.getPayslip(userId));
    }
}
