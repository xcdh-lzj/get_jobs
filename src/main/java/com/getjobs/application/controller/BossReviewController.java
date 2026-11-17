package com.getjobs.application.controller;

import com.getjobs.application.service.*;
import com.getjobs.worker.boss.review.BossReviewPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import java.util.Map;

@RestController
@RequestMapping("/api/boss/review")
@CrossOrigin(origins="*")
@RequiredArgsConstructor
public class BossReviewController {
    private final BossReviewService reviews;
    private final BossResumeTextExtractor resumeText;
    private final BossResumeTargetService resumeTargets;
    public record PreviewRequest(String source,Integer limit,BossReviewPolicy policy) {}
    public record Results(BossReviewStore.Run run) {}
    public record ResumeRequest(String text) {}
    @PostMapping("/resume/extract") public BossResumeTextExtractor.Extracted extract(@RequestParam("file") MultipartFile file) {return resumeText.extract(file);}
    @PostMapping("/resume/analyze") public BossResumeTargetService.Proposal analyze(@RequestBody ResumeRequest request) {return resumeTargets.analyze(request.text());}
    @ExceptionHandler(MaxUploadSizeExceededException.class) @ResponseStatus(HttpStatus.PAYLOAD_TOO_LARGE)
    public Map<String,Object> tooLarge(){return Map.of("success",false,"message","简历不能超过 10MB");}
    @GetMapping("/settings") public BossReviewService.Options settings() { return reviews.options(); }
    @PutMapping("/settings") public Map<String,Object> save(@RequestBody BossReviewService.Options options) {
        reviews.saveOptions(options); return Map.of("success",true,"message","当前账号精准审核设置已保存");
    }
    @PostMapping("/preview") public Map<String,Object> preview(@RequestBody PreviewRequest request) {
        String id=reviews.preview(request.source(),request.limit()==null?20:request.limit(),request.policy());
        return Map.of("success",true,"runId",id,"message","试审已启动，不会打开沟通页面或投递");
    }
    @GetMapping("/results") public Results results(@RequestParam(required=false) String runId,@RequestParam(defaultValue="all") String decision) {
        var result=reviews.results(runId,decision);
        if(runId!=null && result.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"该审核记录不存在或不属于当前账号");
        return new Results(result.orElse(null));
    }
    @PostMapping("/runs/{id}/stop") public Map<String,Object> stop(@PathVariable String id) {
        reviews.stopPreview(id);return Map.of("success",true,"message","已请求停止试审，等待当前 AI 请求结束");
    }
    @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String,Object> invalid(IllegalArgumentException error) { return Map.of("success",false,"message",error.getMessage()); }
}
