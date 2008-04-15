package com.jetbrains.teamsys.dnq.database;

import com.jetbrains.teamsys.database.EntityIterator;
import com.jetbrains.teamsys.database.Entity;
import com.jetbrains.teamsys.database.EntityId;
import com.jetbrains.teamsys.database.TransientEntity;

import java.util.Iterator;

/**
 * Date: 12.03.2007
 * Time: 15:10:33
 *
 * @author Vadim.Gurov
 */
class TransientEntityIterator implements EntityIterator {

  private Iterator<TransientEntity> iter;

  TransientEntityIterator(Iterator<TransientEntity> iterator) {
    this.iter = iterator;
  }

  public boolean hasNext() {
    return iter.hasNext();
  }

  public Entity next() {
    return iter.next();
  }

  public void remove() {
    throw new IllegalArgumentException("Remove from iterator is not supported by transient iterator");
  }

  public EntityId nextId() {
    return iter.next().getId();
  }

  public boolean skip(int number) {
    while (number > 0 && hasNext()) {
      --number;
      next();
    }
    return hasNext();
  }

}
