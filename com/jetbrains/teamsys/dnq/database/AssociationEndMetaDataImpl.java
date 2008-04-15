package com.jetbrains.teamsys.dnq.database;

import com.jetbrains.teamsys.database.AssociationEndMetaData;
import com.jetbrains.teamsys.database.AssociationEndCardinality;
import com.jetbrains.teamsys.database.AssociationMetaData;
import com.jetbrains.teamsys.database.AssociationEndType;
import org.jetbrains.annotations.NotNull;

public class AssociationEndMetaDataImpl implements AssociationEndMetaData {

  private String name;
  private AssociationEndCardinality cardinality;
  private AssociationMetaData associationMetaData;
  private AssociationEndType type;
  private boolean cascadeDelete = false;
  private boolean clearOnDelete = false;

  @NotNull
  public String getName() {
    return name;
  }

  @NotNull
  public AssociationEndCardinality getCardinality() {
    return cardinality;
  }

  @NotNull
  public AssociationMetaData getAssociationMetaData() {
    return associationMetaData;
  }

  @NotNull
  public AssociationEndType getAssociationEndType() {
    return type;
  }

  public boolean getCascadeDelete() {
    return cascadeDelete;
  }

  public boolean getClearOnDelete() {
    return clearOnDelete;
  }

  public void setName(@NotNull String name) {
    this.name = name;
  }

  public void setCardinality(@NotNull AssociationEndCardinality cardinality) {
    this.cardinality = cardinality;
  }

  public void setAssociationMetaData(@NotNull AssociationMetaData associationMetaData) {
    this.associationMetaData = associationMetaData;
    associationMetaData.addEnd(this);
  }

  public void setAssociationEndType(@NotNull AssociationEndType type) {
    this.type = type;
  }

  public void setCascadeDelete(boolean cascadeDelete) {
    this.cascadeDelete = cascadeDelete;
  }

  public void setClearOnDelete(boolean clearOnDelete) {
    this.clearOnDelete = clearOnDelete;
  }
}
