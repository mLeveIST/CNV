package pt.ulisboa.tecnico.cnv.server;


public class Statistics {

  private String query;
  private int mCount;
  private int bbCount;
  private int iCount;
  private int flCount;
  private int fsCount;
  private int lCount;
  private int sCount;


  public Statistics(String query) {
    this.query = query;
    this.mCount = 0;
    this.bbCount = 0;
    this.iCount = 0;
    this.flCount = 0;
    this.fsCount = 0;
    this.lCount = 0;
    this.sCount = 0;
  }

  public String getQuery() {
    return this.query;
  }

  public void setQuery(String query) {
    this.query = query;
  }

  public int getMCount() {
    return this.mCount;
  }

  public void setMCount(int mCount) {
    this.mCount = mCount;
  }

  public void addMCount() {
    this.mCount++;
  }

  public int getBBCount() {
    return this.bbCount;
  }

  public void setBBCount(int bbCount) {
    this.bbCount = bbCount;
  }

  public void addBBCount() {
    this.bbCount++;
  }

  public int getICount() {
    return this.iCount;
  }

  public void setICount(int iCount) {
    this.iCount = iCount;
  }

  public void addICount(int toAdd) {
    this.iCount += toAdd;
  }

  public int getFLCount() {
    return this.flCount;
  }

  public void setFLCount(int flCount) {
    this.flCount = flCount;
  }

  public void addFLCount() {
    this.flCount++;
  }

  public int getFSCount() {
    return this.fsCount;
  }

  public void setFSCount(int fsCount) {
    this.fsCount = fsCount;
  }

  public void addFSCount() {
    this.fsCount++;
  }

  public int getLCount() {
    return this.lCount;
  }

  public void setLCount(int lCount) {
    this.lCount = lCount;
  }

  public void addLCount() {
    this.lCount++;
  }

  public int getSCount() {
    return this.sCount;
  }

  public void setSCount(int sCount) {
    this.sCount = sCount;
  }

  public void addSCount() {
    this.sCount++;
  }
}