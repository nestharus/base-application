package store.entity;

import java.util.Date;

public interface AbstractEntity {
  Date created();

  Date lastModified();

  String createdBy();

  String lastModifiedBy();

  int version();
}
