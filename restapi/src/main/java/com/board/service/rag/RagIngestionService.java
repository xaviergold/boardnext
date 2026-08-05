package com.board.service.rag;

import com.board.util.FileTextExtractor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class RagIngestionService {

    private final VectorStore vectorStore;

    public IngestionResult ingestFolder(String folderPath) {
        File folder = new File(folderPath);
        if (!folder.exists() || !folder.isDirectory()) {
            throw new IllegalArgumentException("폴더가 존재하지 않습니다: " + folderPath);
        }

        File[] files = folder.listFiles();
        if (files == null || files.length == 0) {
            throw new IllegalArgumentException("폴더에 파일이 없습니다: " + folderPath);
        }

        int totalFiles = 0;
        int totalChunks = 0;
        List<String> failedFiles = new ArrayList<>();

        for (File file : files) {
            if (file.isDirectory()) continue;
            try {
                int chunks = ingestFile(file);
                totalChunks += chunks;
                totalFiles++;
                log.info("[RAG] 처리 완료: {} → {}개 청크", file.getName(), chunks);
            } catch (Exception e) {
                log.error("[RAG] 실패: {}", file.getName(), e);
                failedFiles.add(file.getName());
            }
        }

        return new IngestionResult(totalFiles, totalChunks, failedFiles);
    }

    // 파일 경로로 등록 (폴더 등록 시 사용)
    public int ingestFile(File file) throws Exception {
        return ingestFile(file, file.getName());
    }

    // 원본 파일명을 별도로 지정할 수 있는 오버로드 (업로드 시 사용)
    public int ingestFile(File file, String originalFileName) throws Exception {
        // ① 파일 읽기 → byte[]
        byte[] data = Files.readAllBytes(file.toPath());

        // ② MIME 타입 감지
        String mimeType = Files.probeContentType(file.toPath());
        if (mimeType == null) mimeType = "text/plain";

        // ③ 기존 FileTextExtractor로 텍스트 추출
        String text = FileTextExtractor.extract(data, mimeType, originalFileName);
        log.info("[RAG] 텍스트 추출 완료: {} ({}자)", originalFileName, text.length());

        // ④ Spring AI Document로 감싸기 (원본 파일명을 source로 저장)
        Document doc = new Document(text, Map.of(
            "source", originalFileName,
            "path",   file.getAbsolutePath(),
            "mime",   mimeType
        ));

        // ⑤ Chunk 분할
        // 파일 크기가 작은 경우(출석부 등) 전체가 1개 청크로 저장되도록 청크 크기를 크게 설정
        TokenTextSplitter splitter = TokenTextSplitter.builder()
                .withChunkSize(5000)
                .withMinChunkSizeChars(50)
                .withMinChunkLengthToEmbed(20)
                .withMaxNumChunks(10000)
                .withKeepSeparator(true)
                .build();
        List<Document> chunks = splitter.apply(List.of(doc));
        log.info("[RAG] 청크 분할: {} → {}개", originalFileName, chunks.size());

        // ⑥ Embedding + PGVector 저장
        vectorStore.add(chunks);
        log.info("[RAG] PGVector 저장 완료: {}개 청크", chunks.size());

        return chunks.size();
    }

    public record IngestionResult(int totalFiles, int totalChunks, List<String> failedFiles) {}
}