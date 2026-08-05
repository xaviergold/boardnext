package com.board.controller;

import com.board.service.rag.RagIngestionService;
import com.board.service.rag.TrainingFileParserService;
import com.board.service.rag.TrainingFileParserService.FileType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/rag")
@RequiredArgsConstructor
public class RESTRagIngestionController {

    private final RagIngestionService ingestionService;
    private final TrainingFileParserService trainingFileParserService;

    /**
     * 폴더 파일 목록 조회 (화면 목록 표시용)
     * GET /api/rag/folder/list?path=/home/boardnext/documents
     */
    @GetMapping("/folder/list")
    public ResponseEntity<?> listFolder(@RequestParam String path) {
        File folder = new File(path);
        if (!folder.exists() || !folder.isDirectory()) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "폴더가 존재하지 않습니다: " + path
            ));
        }

        File[] files = folder.listFiles();
        if (files == null || files.length == 0) {
            return ResponseEntity.ok(Map.of("files", List.of()));
        }

        List<Map<String, Object>> fileList = Arrays.stream(files)
            .filter(File::isFile)
            .map(f -> Map.<String, Object>of(
                "name", f.getName(),
                "size", f.length(),
                "path", f.getAbsolutePath()
            ))
            .collect(Collectors.toList());

        return ResponseEntity.ok(Map.of("files", fileList));
    }

    /**
     * 폴더 전체 임베딩
     * POST /api/rag/ingest/folder
     * Body: { "folderPath": "/home/boardnext/documents" }
     */
    @PostMapping("/ingest/folder")
    public ResponseEntity<Map<String, Object>> ingestFolder(
            @RequestBody Map<String, String> body) {

        String folderPath = body.get("folderPath");
        RagIngestionService.IngestionResult result = ingestionService.ingestFolder(folderPath);

        return ResponseEntity.ok(Map.of(
            "status", "완료",
            "totalFiles", result.totalFiles(),
            "totalChunks", result.totalChunks(),
            "failedFiles", result.failedFiles()
        ));
    }

    /**
     * 파일 1개 multipart 업로드 후 임베딩
     * POST /api/rag/ingest/upload
     * Content-Type: multipart/form-data
     */
    @PostMapping("/ingest/upload")
    public ResponseEntity<Map<String, Object>> ingestUpload(
            @RequestParam("file") MultipartFile file) {

        // 임시 파일 생성 후 처리
        File tempFile = null;
        String originalFileName = file.getOriginalFilename();
        try {
            // 파일명에 경로 구분자(\, /)와 특수문자가 포함될 수 있으므로
            // 임시파일 suffix에는 확장자만 사용
            String ext = "";
            if (originalFileName != null && originalFileName.contains(".")) {
                ext = originalFileName.substring(originalFileName.lastIndexOf("."));
            }
            // 파일명에서 경로 구분자 제거 (디렉토리 선택 시 경로 포함될 수 있음)
            if (originalFileName != null) {
                originalFileName = originalFileName.replaceAll(".*[/\\\\]", "").trim();
            }
            tempFile = File.createTempFile("rag_", ext);
            file.transferTo(tempFile);

            // 파일 종류 감지 → 출석부/참여자명단은 DB 저장, 일반 문서는 Vector DB
            FileType fileType = trainingFileParserService.detectFileType(tempFile);
            log.info("[Ingestion] 파일 종류: {} → {}", originalFileName, fileType);

            if (fileType == FileType.ATTENDANCE) {
                // 출석부 → training_course + training_student 저장
                int count = trainingFileParserService.parseAttendanceFile(tempFile, originalFileName);
                return ResponseEntity.ok(Map.of(
                    "status", "완료",
                    "file", originalFileName,
                    "type", "출석부",
                    "totalChunks", count
                ));
            } else if (fileType == FileType.PARTICIPANT) {
                // 참여자명단 → company, phone UPDATE
                int count = trainingFileParserService.parseParticipantFile(tempFile, originalFileName);
                return ResponseEntity.ok(Map.of(
                    "status", "완료",
                    "file", originalFileName,
                    "type", "참여자명단",
                    "totalChunks", count
                ));
            } else {
                // 일반 문서 → Vector DB
                int chunks = ingestionService.ingestFile(tempFile, originalFileName);
                return ResponseEntity.ok(Map.of(
                    "status", "완료",
                    "file", originalFileName,
                    "type", "문서",
                    "totalChunks", chunks
                ));
            }
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                "status", "실패",
                "file", originalFileName,
                "error", e.getMessage()
            ));
        } finally {
            // 임시 파일 반드시 삭제
            if (tempFile != null && tempFile.exists()) {
                tempFile.delete();
            }
        }
    }
}