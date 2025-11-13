package org.skypulse.config.utils;

import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.Unmarshaller;
import org.w3c.dom.Document;

public class XmlUtil {

    /**
     *  Convert XML → Java object.
     * Generic Method — <T> means this method works for any Java class type you pass (e.g., User, Config, etc.).
     * Input Parameters:
     *      Document xmlDoc: the XML content already parsed into a DOM structure.
     *      Class<T> type: the Java class you want the XML converted into.
     * JAXBContext — creates a context aware of the class type.
     * Throws Exception: Caller must handle or propagate exceptions (e.g., invalid XML).
     */

    @SuppressWarnings("unhecked")
    public static <T> T unmarshal(Document xmlDoc, Class<T> type) throws Exception {
        JAXBContext ctx = JAXBContext.newInstance(type);
        Unmarshaller um = ctx.createUnmarshaller();
        return (T) um.unmarshal(xmlDoc);
    }
}
