package com.vnsearch.soap;

import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.ws.config.annotation.EnableWs;
import org.springframework.ws.config.annotation.WsConfigurerAdapter;
import org.springframework.ws.transport.http.MessageDispatcherServlet;
import org.springframework.ws.wsdl.wsdl11.DefaultWsdl11Definition;
import org.springframework.xml.xsd.SimpleXsdSchema;
import org.springframework.xml.xsd.XsdSchema;

/**
 * Gắn tầng SOAP vào ứng dụng.
 *
 * <pre>
 *   POST /ws                       gửi thông điệp SOAP
 *   GET  /ws/tim-kiem.wsdl         lấy hợp đồng WSDL (sinh tự động từ XSD)
 * </pre>
 *
 * <h2>Vì sao SOAP nằm dưới {@code /ws} chứ không dùng chung {@code /api}</h2>
 *
 * <p>Hai lý do, cả hai đều thực dụng:
 *
 * <ol>
 *   <li><b>Luật bảo mật viết theo tiền tố đường dẫn.</b> Một tiền tố riêng
 *       nghĩa là mở hay đóng cửa SOAP là sửa đúng một dòng trong
 *       {@code PublicEndpoints}, không phải rà từng endpoint.</li>
 *   <li><b>{@link MessageDispatcherServlet} là một servlet KHÁC</b> với
 *       {@code DispatcherServlet} của Spring MVC. Cho hai servlet cùng nhận
 *       {@code /api/*} là mở đường cho những sự cố định tuyến chỉ xuất hiện
 *       trong một số thứ tự đăng ký nhất định.</li>
 * </ol>
 *
 * <h2>Vì sao WSDL được SINH RA chứ không viết tay</h2>
 *
 * <p>{@link DefaultWsdl11Definition} dựng WSDL từ chính tệp XSD lúc chạy. Một
 * tệp WSDL viết tay là bản sao thứ hai của cùng một hợp đồng, và hai bản sao
 * thì sớm muộn lệch nhau — thường là sau khi ai đó thêm một phần tử vào XSD mà
 * quên WSDL. Bên gọi đọc WSDL, sinh mã máy khách theo nó, rồi nhận về một
 * thông điệp không khớp.
 */
@EnableWs
@Configuration
public class SoapWebServiceConfig extends WsConfigurerAdapter {

    /**
     * Servlet của SOAP, đăng ký ở {@code /ws/*}.
     *
     * <p>{@code setTransformWsdlLocations(true)} là dòng dễ quên nhất và hậu
     * quả của nó chỉ lộ ra sau khi triển khai: không có nó, WSDL trả về ghi
     * cứng địa chỉ {@code localhost:8082} lấy từ lúc dựng, nên máy khách nào
     * tải WSDL qua Gateway cũng nhận được một địa chỉ họ không gọi tới được.
     * Bật lên thì địa chỉ được viết lại theo đúng host trong request.
     */
    @Bean
    public ServletRegistrationBean<MessageDispatcherServlet> soapServlet(
            ApplicationContext context) {
        MessageDispatcherServlet servlet = new MessageDispatcherServlet();
        servlet.setApplicationContext(context);
        servlet.setTransformWsdlLocations(true);
        return new ServletRegistrationBean<>(servlet, "/ws/*");
    }

    @Bean(name = "tim-kiem")
    public DefaultWsdl11Definition wsdl(XsdSchema timKiemSchema) {
        DefaultWsdl11Definition definition = new DefaultWsdl11Definition();
        definition.setPortTypeName("TimKiemPort");
        definition.setLocationUri("/ws");
        definition.setTargetNamespace("http://vnsearch.com/soap/tim-kiem/v1");
        definition.setSchema(timKiemSchema);
        return definition;
    }

    @Bean
    public XsdSchema timKiemSchema() {
        Resource xsd = new ClassPathResource("wsdl/tim-kiem.xsd");
        return new SimpleXsdSchema(xsd);
    }
}
