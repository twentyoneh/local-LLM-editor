package ru.localdoc.document;

import org.w3c.dom.*;
import ru.localdoc.domain.DocumentModel;
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.zip.CRC32;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamWriter;

public final class OdtHandler implements DocumentHandler {
    private static final String TEXT = "urn:oasis:names:tc:opendocument:xmlns:text:1.0";
    private static final String OFFICE = "urn:oasis:names:tc:opendocument:xmlns:office:1.0";
    private static final String TABLE = "urn:oasis:names:tc:opendocument:xmlns:table:1.0";
    private static final String DRAW = "urn:oasis:names:tc:opendocument:xmlns:drawing:1.0";

    @Override public Imported read(Path path, Charset ignored) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newDefaultInstance();
        f.setNamespaceAware(true); f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        f.setAttribute("http://www.oracle.com/xml/jaxp/properties/maxElementDepth",256);
        try (var zip = new ZipFile(path.toFile()); var in = zip.getInputStream(zip.getEntry("content.xml"))) {
            var dom = f.newDocumentBuilder().parse(in);
            var body = dom.getElementsByTagNameNS(OFFICE, "text");
            if (body.getLength() != 1) throw new IllegalArgumentException("Не найден текстовый документ ODT");
            List<String> paragraphs = new ArrayList<>();
            collect(body.item(0), paragraphs);
            List<String> warnings = new ArrayList<>();
            if (dom.getElementsByTagNameNS(TABLE,"table").getLength() > 0) warnings.add("Таблицы не входят в текстовый импорт.");
            if (dom.getElementsByTagNameNS(DRAW,"frame").getLength() > 0) warnings.add("Изображения и текстовые рамки не входят в текстовый импорт.");
            if (dom.getElementsByTagNameNS(TEXT,"note").getLength() > 0) warnings.add("Сноски не входят в текстовый импорт.");
            if (dom.getElementsByTagNameNS(TEXT,"tracked-changes").getLength() > 0 || dom.getElementsByTagNameNS(OFFICE,"annotation").getLength() > 0)
                warnings.add("История исправлений и комментарии не входят в текстовый импорт.");
            if (dom.getElementsByTagNameNS(TEXT,"span").getLength() > 0 || dom.getElementsByTagNameNS(TEXT,"h").getLength() > 0 || dom.getElementsByTagNameNS(TEXT,"list").getLength() > 0)
                warnings.add("Стили, заголовки и оформление списков будут заменены обычным текстом.");
            // Header/footer definitions live outside content.xml.
            var styles = zip.getEntry("styles.xml");
            if (styles != null) {
                try (var stream = zip.getInputStream(styles)) {
                    var styleDom = f.newDocumentBuilder().parse(stream);
                    String ns = "urn:oasis:names:tc:opendocument:xmlns:style:1.0";
                    if (styleDom.getElementsByTagNameNS(ns,"header").getLength() > 0 || styleDom.getElementsByTagNameNS(ns,"footer").getLength() > 0)
                        warnings.add("Колонтитулы не входят в текстовый импорт.");
                }
            }
            return new Imported(new DocumentModel(paragraphs), warnings);
        }
    }
    private void collect(Node parent, List<String> out) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (TABLE.equals(n.getNamespaceURI()) || DRAW.equals(n.getNamespaceURI()) || (TEXT.equals(n.getNamespaceURI()) && "tracked-changes".equals(n.getLocalName())) || (OFFICE.equals(n.getNamespaceURI()) && "annotation".equals(n.getLocalName()))) continue;
            if (TEXT.equals(n.getNamespaceURI()) && ("p".equals(n.getLocalName()) || "h".equals(n.getLocalName()))) out.add(text(n));
            else collect(n, out);
        }
    }
    private String text(Node parent) {
        StringBuilder out = new StringBuilder();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n.getNodeType() == Node.TEXT_NODE) out.append(n.getNodeValue());
            else if (TEXT.equals(n.getNamespaceURI()) && "s".equals(n.getLocalName())) {
                String count = ((Element)n).getAttributeNS(TEXT,"c");
                int c = count.isEmpty() ? 1 : Integer.parseInt(count);
                if (c < 1 || c > 100_000) throw new IllegalArgumentException("Некорректное число пробелов в ODT");
                out.append(" ".repeat(c));
            } else if (TEXT.equals(n.getNamespaceURI()) && "tab".equals(n.getLocalName())) out.append('\t');
            else if (TEXT.equals(n.getNamespaceURI()) && "line-break".equals(n.getLocalName())) out.append('\n');
            else if (!(DRAW.equals(n.getNamespaceURI()) || (TEXT.equals(n.getNamespaceURI()) && "note".equals(n.getLocalName())) || (OFFICE.equals(n.getNamespaceURI()) && "annotation".equals(n.getLocalName())))) out.append(text(n));
        }
        return out.toString();
    }
    @Override public void write(DocumentModel model, Path path, Charset ignored) throws Exception {
        // Write a standard ODF 1.2 package directly. The JDK UTF-8 writer preserves
        // supplementary Unicode characters that legacy ODF serializers corrupt.
        var content=new ByteArrayOutputStream();
        XMLStreamWriter xml=XMLOutputFactory.newDefaultFactory().createXMLStreamWriter(content,"UTF-8");
        xml.writeStartDocument("UTF-8","1.0");xml.writeStartElement("office","document-content",OFFICE);
        xml.writeNamespace("office",OFFICE);xml.writeNamespace("text",TEXT);xml.writeAttribute("office",OFFICE,"version","1.2");
        xml.writeStartElement("office","body",OFFICE);xml.writeStartElement("office","text",OFFICE);
        for (String value:model.paragraphs()) {
            xml.writeStartElement("text","p",TEXT);
            for (int i=0;i<value.length();) {
                char c=value.charAt(i);
                if (c==' ') {
                    int end=i+1;while (end<value.length() && value.charAt(end)==' ' && end-i<100000) end++;
                    xml.writeEmptyElement("text","s",TEXT);if (end-i>1) xml.writeAttribute("text",TEXT,"c",Integer.toString(end-i));i=end;
                } else if (c=='\t' || c=='\n') { xml.writeEmptyElement("text",c=='\t'?"tab":"line-break",TEXT);i++; }
                else {
                    int end=i+1;while (end<value.length() && value.charAt(end)!=' ' && value.charAt(end)!='\t' && value.charAt(end)!='\n') end++;
                    xml.writeCharacters(value.substring(i,end));i=end;
                }
            }
            xml.writeEndElement();
        }
        xml.writeEndElement();xml.writeEndElement();xml.writeEndElement();xml.writeEndDocument();xml.close();
        String styles="<?xml version=\"1.0\" encoding=\"UTF-8\"?><office:document-styles xmlns:office=\""+OFFICE+"\" office:version=\"1.2\"><office:styles/><office:automatic-styles/><office:master-styles/></office:document-styles>";
        String manifest="""
            <?xml version="1.0" encoding="UTF-8"?>
            <manifest:manifest xmlns:manifest="urn:oasis:names:tc:opendocument:xmlns:manifest:1.0" manifest:version="1.2">
              <manifest:file-entry manifest:full-path="/" manifest:media-type="application/vnd.oasis.opendocument.text" manifest:version="1.2"/>
              <manifest:file-entry manifest:full-path="content.xml" manifest:media-type="text/xml"/>
              <manifest:file-entry manifest:full-path="styles.xml" manifest:media-type="text/xml"/>
            </manifest:manifest>
            """;
        try (var out=new ZipOutputStream(Files.newOutputStream(path),StandardCharsets.UTF_8)) {
            byte[] type="application/vnd.oasis.opendocument.text".getBytes(StandardCharsets.US_ASCII);
            ZipEntry first=new ZipEntry("mimetype");CRC32 crc=new CRC32();crc.update(type);
            first.setMethod(ZipEntry.STORED);first.setSize(type.length);first.setCompressedSize(type.length);first.setCrc(crc.getValue());
            out.putNextEntry(first);out.write(type);out.closeEntry();
            entry(out,"content.xml",content.toByteArray());entry(out,"styles.xml",styles.getBytes(StandardCharsets.UTF_8));entry(out,"META-INF/manifest.xml",manifest.getBytes(StandardCharsets.UTF_8));
        }
    }
    private void entry(ZipOutputStream out,String name,byte[] bytes) throws Exception { out.putNextEntry(new ZipEntry(name));out.write(bytes);out.closeEntry(); }
}
