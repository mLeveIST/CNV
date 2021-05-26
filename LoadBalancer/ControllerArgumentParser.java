package pt.ulisboa.tecnico.cnv.controller;

import org.apache.commons.cli.*;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

public class ControllerArgumentParser {

  public enum ServerParameters {
    ADDRESS("address"),
    PORT("port");

    private String text;

    ServerParameters(final String text) {
      this.text = text;
    }

    @Override
    public String toString() {
      return this.text;
    }
  }

  private final Options options = new Options();
  private final Map<String, Object> argValues = new HashMap<>();
  private final CommandLineParser parser = new DefaultParser();
  private CommandLine cmd = null;

  ControllerArgumentParser(final String[] args) {
    this.setupCLIOptions();

    try {
      this.cmd = this.parser.parse(this.options, args);
    } catch (ParseException pe) {
      System.out.println(pe.getMessage());
      System.exit(1);
    }

    this.parseValues();
  }

  private void setupCLIOptions() {
    Option addressOption = new Option(
      ServerParameters.ADDRESS.toString(), true,
      "server listen address (default: 127.0.0.1).");
    addressOption.setRequired(false);
    this.options.addOption(addressOption);

    Option portOption = new Option(
      ServerParameters.PORT.toString(), true,
      "server listen port (default: 8000).");
    portOption.setRequired(false);
    this.options.addOption(portOption);
  }

  private void parseValues() {
    if (this.cmd.hasOption(ServerParameters.ADDRESS.toString())) {
      final String address = this.cmd.getOptionValue(ServerParameters.ADDRESS.toString());
      this.argValues.put(ServerParameters.ADDRESS.toString(), address);
    } else {
      this.argValues.put(ServerParameters.ADDRESS.toString(), "127.0.0.1");
    }

    if (this.cmd.hasOption(ServerParameters.PORT.toString())) {
      final String port = this.cmd.getOptionValue(ServerParameters.PORT.toString());
      this.argValues.put(ServerParameters.PORT.toString(), new Integer(port));
    } else {
      this.argValues.put(ServerParameters.PORT.toString(), new Integer(8000));
    }
  }

  public String getServerAddress() {
    return (String) this.argValues.get(ServerParameters.ADDRESS.toString());
  }

  public Integer getServerPort() {
    return (Integer) this.argValues.get(ServerParameters.PORT.toString());
  }
}
