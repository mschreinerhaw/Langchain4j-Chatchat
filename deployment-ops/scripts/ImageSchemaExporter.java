import java.nio.file.*;import java.util.*;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
/** Release-time metadata export only. No application Spring context or live database is opened. */
public class ImageSchemaExporter {
 public static void main(String[] args)throws Exception{
  Path target=Path.of(args[0]);if(Files.exists(target))throw new IllegalArgumentException("Use a new schema output");
  var factory=new LocalContainerEntityManagerFactoryBean();
  factory.setDataSource(new DriverManagerDataSource("jdbc:h2:mem:release_schema;DB_CLOSE_DELAY=-1","sa",""));
  factory.setPackagesToScan(Arrays.copyOfRange(args,1,args.length));factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
  Map<String,Object> settings=new HashMap<>();settings.put("hibernate.dialect","org.hibernate.dialect.PostgreSQLDialect");settings.put("hibernate.boot.allow_jdbc_metadata_access",false);
  settings.put("hibernate.physical_naming_strategy","org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy");settings.put("hibernate.hbm2ddl.delimiter",";");settings.put("hibernate.format_sql",true);
  settings.put("jakarta.persistence.schema-generation.database.action","none");settings.put("jakarta.persistence.schema-generation.scripts.action","create");settings.put("jakarta.persistence.schema-generation.scripts.create-target",target.toAbsolutePath().toString());
  factory.setJpaPropertyMap(settings);factory.afterPropertiesSet();factory.destroy();
  if(!Files.isRegularFile(target)||!Files.readString(target).contains("create table"))throw new IllegalStateException("No schema was exported");
  System.out.println("Exported entity metadata without application startup or production data.");
 }
}
