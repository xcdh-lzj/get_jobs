package com.getjobs.application.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import javax.xml.stream.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;

/** 只在本机提取文本，临时文件用完即删除；不保存原简历，也不调用 AI。 */
@Service
public class BossResumeTextExtractor {
    public static final int MAX_TEXT=30_000;
    private static final long MAX_BYTES=10L*1024*1024;
    public record Extracted(String fileName,String text,List<String> notices) {}

    public Extracted extract(MultipartFile file) {
        if(file==null || file.isEmpty())throw new IllegalArgumentException("请选择 PDF、DOCX、TXT 或 Markdown 简历");
        if(file.getSize()>MAX_BYTES)throw new IllegalArgumentException("简历不能超过 10MB");
        String name=Objects.requireNonNullElse(file.getOriginalFilename(),"resume").replace('\\','/');
        name=name.substring(name.lastIndexOf('/')+1);if(name.length()>180)name=name.substring(name.length()-180);
        String extension=name.contains(".")?name.substring(name.lastIndexOf('.')+1).toLowerCase(Locale.ROOT):"";
        if(!Set.of("pdf","docx","txt","md").contains(extension))throw new IllegalArgumentException("仅支持 PDF、DOCX、TXT、MD；图片或扫描件请先转为可复制的文字");
        Path temporary=null;
        try {
            temporary=Files.createTempFile("getjobs-resume-","."+extension);file.transferTo(temporary);
            if(Files.size(temporary)>MAX_BYTES)throw new IllegalArgumentException("简历不能超过 10MB");
            String text=switch(extension) {case "pdf" -> pdf(temporary);case "docx" -> docx(temporary);default -> utf8(Files.readAllBytes(temporary));};
            text=redact(text);
            if(text.replaceAll("\\s","").length()<40)throw new IllegalArgumentException("未提取到足够的简历文字；扫描版 PDF 请先做文字识别，或粘贴技能与项目经历");
            return new Extracted(name,text,List.of("已清理常见邮箱、手机号和个人信息行；发送 AI 前仍可检查和修改文字。","原简历不长期保存，分析结果不会自动修改当前账号设置。"));
        } catch(InvalidPasswordException e) {throw new IllegalArgumentException("PDF 已加密，请上传可读取的简历");}
        catch(IllegalArgumentException e) {throw e;}
        catch(Exception e) {throw new IllegalArgumentException("无法解析此简历，请检查文件是否损坏，或改用 PDF、DOCX、UTF-8 文本");}
        finally {if(temporary!=null)try{Files.deleteIfExists(temporary);}catch(IOException ignored){}}
    }
    private String pdf(Path file) throws IOException {
        try(var document=Loader.loadPDF(file.toFile())) {
            if(!document.getCurrentAccessPermission().canExtractContent())throw new IllegalArgumentException("此 PDF 不允许提取文字，请使用可读取的版本");
            if(document.getNumberOfPages()>20)throw new IllegalArgumentException("简历最多 20 页，请保留相关经历后上传");
            var writer=new LimitedWriter();var stripper=new PDFTextStripper();stripper.setSortByPosition(true);stripper.writeText(document,writer);return writer.toString();
        }
    }
    private String docx(Path file) throws Exception {
        try(var zip=new ZipFile(file.toFile())) {
            var entry=zip.getEntry("word/document.xml");
            if(entry==null || entry.getSize()>4_000_000)throw new IllegalArgumentException("DOCX 内容不完整或过大");
            byte[] xml;try(var stream=zip.getInputStream(entry)){xml=stream.readNBytes(4_000_001);}
            if(xml.length>4_000_000)throw new IllegalArgumentException("DOCX 内容过大");
            var factory=XMLInputFactory.newFactory();factory.setProperty(XMLInputFactory.SUPPORT_DTD,false);factory.setProperty("javax.xml.stream.isSupportingExternalEntities",false);
            var reader=factory.createXMLStreamReader(new ByteArrayInputStream(xml));var out=new LimitedWriter();boolean text=false;
            try {
                while(reader.hasNext()) {
                    int event=reader.next();
                    if(event==XMLStreamConstants.DTD || event==XMLStreamConstants.ENTITY_REFERENCE)throw new IllegalArgumentException("DOCX 包含不支持的 XML 内容");
                    if(event==XMLStreamConstants.START_ELEMENT){String local=reader.getLocalName();if("t".equals(local))text=true;if("tab".equals(local))out.write(" ");}
                    else if(event==XMLStreamConstants.CHARACTERS && text)out.write(reader.getText());
                    else if(event==XMLStreamConstants.END_ELEMENT){String local=reader.getLocalName();if("t".equals(local))text=false;if("p".equals(local)||"tr".equals(local))out.write("\n");}
                }
            }finally {reader.close();}
            return out.toString();
        }
    }
    private String utf8(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().replace("\uFEFF","");
    }
    public static String redact(String raw) {
        if(raw==null || raw.isBlank())throw new IllegalArgumentException("请提供简历中的技能、项目或工作经历");
        if(raw.length()>MAX_TEXT)throw new IllegalArgumentException("简历文字不能超过 30000 字，请精简相关经历");
        return raw.replaceAll("(?im)^\\s*(?:姓名|姓\\s*名|电话|手机|手机号|联系电话|邮箱|电子邮箱|联系地址|现居地址|身份证|出生日期|生日|微信|QQ|Name|Phone|Email|Address)\\s*[:：].*$","[个人信息已清理]")
                .replaceAll("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}","[邮箱已清理]")
                .replaceAll("(?<!\\d)(?:\\+?86[- ]?)?1[3-9]\\d(?:[- ]?\\d){8}(?!\\d)","[手机号已清理]")
                .replaceAll("(?<!\\d)[1-9]\\d{16}[\\dXx](?!\\d)","[证件号已清理]")
                .replace('\u0000',' ').strip();
    }
    private static final class LimitedWriter extends Writer {
        private final StringBuilder text=new StringBuilder();
        @Override public void write(char[] buffer,int offset,int count) {if(text.length()+count>MAX_TEXT)throw new IllegalArgumentException("简历文字不能超过 30000 字，请精简后上传");text.append(buffer,offset,count);}
        @Override public void flush() {}
        @Override public void close() {}
        @Override public String toString(){return text.toString();}
    }
}
