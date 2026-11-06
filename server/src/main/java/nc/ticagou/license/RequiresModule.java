package nc.ticagou.license;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Contrôleur d'un module optionnel : les modifications sont refusées si la licence ne le comprend pas. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresModule {
    LicenseModule value();
}
