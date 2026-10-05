package com.progresstracker.progresstracker.dto.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.TYPE_USE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * A region-based IANA time zone id, such as {@code America/Los_Angeles} or {@code UTC}, that this
 * database knows too. Null is allowed.
 */
@Documented
@Constraint(validatedBy = TimeZoneIdValidator.class)
@Target({METHOD, FIELD, ANNOTATION_TYPE, PARAMETER, TYPE_USE})
@Retention(RUNTIME)
public @interface TimeZoneId {

    String message() default "must be an IANA time zone such as America/Los_Angeles";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
