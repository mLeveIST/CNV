package pt.ulisboa.tecnico.cnv.server;


public class Statistics {

  private int i_count;
  private int m_count;
  private String query;


  public Statistics(String query) {
    this.i_count = 0;
    this.m_count = 0;
    this.query = query;
  }

  public int getICount() {
    return this.i_count;
  }

  public void setICount(int i_count) {
    this.i_count = i_count;
  }

  public void addICount(int toAdd) {
    this.i_count += toAdd;
  }

  public int getMCount() {
    return this.m_count;
  }

  public void setMCount(int m_count) {
    this.m_count = m_count;
  }

  public void addMCount() {
    this.m_count++;
  }

  public String getQuery() {
    return this.query;
  }

  public void setQuery(String query) {
    this.query = query;
  }
}