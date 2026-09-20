package com.zcw.chatai.data.doc

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper

/** PDF 文本层抽取（PdfBox-Android）。扫描件（无文本层）返回空文本，由调用方提示。 */
internal object PdfDocumentParser {

    fun parse(fileBytes: ByteArray): ParsedDocument {
        PDDocument.load(fileBytes).use { doc ->
            if (doc.isEncrypted) throw DocumentException("该 PDF 已加密，暂不支持解析")
            val pages = doc.numberOfPages
            val raw = PDFTextStripper().getText(doc).trim()
            if (raw.isEmpty()) {
                throw DocumentException("这个 PDF 里没有可提取的文字（可能是扫描件，暂不支持 OCR）")
            }
            return ParsedDocument(text = collapseBlankLines(raw), meta = "共 $pages 页")
        }
    }
}
