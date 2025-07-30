package store.entity;

import java.util.Date;

import com.geldata.driver.annotations.GelType;
import lombok.*;

@GelType
@Builder
public record User(
    String email,
    String id,
    Date created,
    Date lastModified,
    String createdBy,
    String lastModifiedBy,
    int version)
    implements BaseEntity, AbstractEntity {}
