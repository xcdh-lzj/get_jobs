package com.getjobs.application.service;

import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class BossResumeTextExtractorTest {
    private final BossResumeTextExtractor extractor=new BossResumeTextExtractor();
    private static final String PHONE="138"+"0000"+"0000"; // 合成号码，只测试联系方式脱敏。
    private static final String RESUME="姓名：测试候选人\n邮箱：resume@example.invalid\n手机："+PHONE+"\n技能：Python、Java、FastAPI、RAG。\n项目：负责开发企业知识库问答服务，使用 Python 实现文档解析、向量检索、RAG 问答和 Agent 工具调用，完成后端接口开发、测试和部署。";
    @Test void textExtractionRedactsContactsAndRetainsProfessionalEvidence() {
        var result=extractor.extract(file("../../resume.txt",RESUME.getBytes(StandardCharsets.UTF_8)));
        assertEquals("resume.txt",result.fileName());assertFalse(result.text().contains("resume@example.invalid"));assertFalse(result.text().contains(PHONE));
        assertTrue(result.text().contains("负责开发企业知识库问答服务"));assertFalse(result.text().contains("测试候选人"));
    }
    @Test void docxPreservesParagraphsAndChineseProjectDescriptions() throws Exception {
        String xml="<w:document xmlns:w='http://schemas.openxmlformats.org/wordprocessingml/2006/main'><w:body><w:p><w:r><w:t>"+RESUME+"</w:t></w:r></w:p></w:body></w:document>";
        var result=extractor.extract(file("resume.docx",docx(xml)));assertTrue(result.text().contains("RAG 问答和 Agent 工具调用"));assertFalse(result.text().contains("resume@example.invalid"));
    }
    @Test void pdfTextIsExtractedAndImageOnlyPdfDoesNotInventExperience() throws Exception {
        byte[] populated,blank;
        try(var doc=new PDDocument();var out=new ByteArrayOutputStream()) {
            var page=new PDPage();doc.addPage(page);
            try(var content=new PDPageContentStream(doc,page)) {content.beginText();content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),11);content.newLineAtOffset(40,700);content.showText("Python developer: built Agent backend services, RAG retrieval and REST API integrations.");content.endText();}
            doc.save(out);populated=out.toByteArray();
        }
        assertTrue(extractor.extract(file("resume.pdf",populated)).text().contains("Agent backend services"));
        try(var doc=new PDDocument();var out=new ByteArrayOutputStream()){doc.addPage(new PDPage());doc.save(out);blank=out.toByteArray();}
        assertThrows(IllegalArgumentException.class,()->extractor.extract(file("scanned.pdf",blank)));
    }
    @Test void unsupportedFilesBadUtf8AndExternalXmlEntitiesAreRejected() throws Exception {
        assertThrows(IllegalArgumentException.class,()->extractor.extract(file("resume.exe",new byte[]{1,2,3})));
        assertThrows(IllegalArgumentException.class,()->extractor.extract(file("resume.txt",new byte[]{(byte)0xff,(byte)0xfe})));
        byte[] malicious=docx("<!DOCTYPE x [<!ENTITY leak SYSTEM 'file:///etc/hosts'>]><w:document xmlns:w='http://schemas.openxmlformats.org/wordprocessingml/2006/main'><w:t>&leak;</w:t></w:document>");
        assertThrows(IllegalArgumentException.class,()->extractor.extract(file("resume.docx",malicious)));
        assertThrows(IllegalArgumentException.class,()->BossResumeTextExtractor.redact("x".repeat(30_001)));
    }
    private MockMultipartFile file(String name,byte[] body){return new MockMultipartFile("file",name,"application/octet-stream",body);}
    private byte[] docx(String xml) throws IOException {var out=new ByteArrayOutputStream();try(var zip=new ZipOutputStream(out)){zip.putNextEntry(new ZipEntry("word/document.xml"));zip.write(xml.getBytes(StandardCharsets.UTF_8));zip.closeEntry();}return out.toByteArray();}
}
