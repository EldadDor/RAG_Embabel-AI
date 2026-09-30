package com.dex.ragpoc.parsing

import org.apache.poi.sl.usermodel.PictureData
import org.apache.poi.sl.usermodel.Placeholder
import org.apache.poi.xslf.usermodel.XMLSlideShow
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

object PresentationFixtures {
    fun image(color: Color = Color.RED): ByteArray =
        ByteArrayOutputStream().use { output ->
            val image = BufferedImage(2, 3, BufferedImage.TYPE_INT_RGB)
            image.createGraphics().let { graphics ->
                graphics.color = color
                graphics.fillRect(0, 0, 2, 3)
                graphics.dispose()
            }
            ImageIO.write(image, "png", output)
            output.toByteArray()
        }

    fun write(
        path: Path,
        image: ByteArray = image(),
    ): Path {
        Files.createDirectories(path.parent)
        XMLSlideShow().use { show ->
            show.properties.coreProperties.title = "Operations Slides"
            val overview = show.createSlide()
            overview.createTextBox().apply {
                setPlaceholder(Placeholder.TITLE)
                text = "Overview"
            }
            overview.createTextBox().apply {
                text = "ניתן לצרף קבצים — Hebrew content"
                addNewTextParagraph().apply {
                    setBullet(true)
                    addNewTextRun().setText("Check readiness")
                }
            }
            overview.createTable(2, 2).apply {
                getCell(0, 0).text = "Setting"
                getCell(0, 1).text = "Value"
                getCell(1, 0).text = "Mode"
                getCell(1, 1).text = "Local | stable"
            }
            overview
                .createGroup()
                .createGroup()
                .createTextBox()
                .text = "Nested group text"
            show
                .getNotesSlide(overview)
                .shapes
                .filterIsInstance<org.apache.poi.xslf.usermodel.XSLFTextShape>()
                .first { it.textType == Placeholder.BODY }
                .text = "Speaker explanation"
            val picture = show.addPicture(image, PictureData.PictureType.PNG)
            val pictures = show.createSlide()
            pictures.xmlObject.show = false
            pictures.createPicture(picture)
            pictures.createPicture(picture)
            show.createSlide()
            val closing = show.createSlide()
            closing.createTextBox().text = "Closing before overview"
            show.setSlideOrder(closing, 0)
            Files.newOutputStream(path).use(show::write)
        }
        return path
    }
}
