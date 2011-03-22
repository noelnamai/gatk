/*
 * Copyright (c) 2010 The Broad Institute
 *
 * Permission is hereby granted, free of charge, to any person
 * obtaining a copy of this software and associated documentation
 * files (the "Software"), to deal in the Software without
 * restriction, including without limitation the rights to use,
 * copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
 * OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR
 * THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package org.broadinstitute.sting.commandline;

import org.apache.log4j.*;
import org.broadinstitute.sting.gatk.CommandLineGATK;
import org.broadinstitute.sting.utils.exceptions.ReviewedStingException;
import org.broadinstitute.sting.utils.help.ApplicationDetails;
import org.broadinstitute.sting.utils.help.HelpFormatter;

import java.io.IOException;
import java.util.*;

public abstract class CommandLineProgram {

    /** The command-line program and the arguments it returned. */
    protected ParsingEngine parser = null;

    /** the default log level */
    @Argument(fullName = "logging_level",
              shortName = "l",
              doc = "Set the minimum level of logging, i.e. setting INFO get's you INFO up to FATAL, setting ERROR gets you ERROR and FATAL level logging.",
              required = false)
    protected String logging_level = "INFO";


    /** where to send the output of our logger */
    @Output(fullName = "log_to_file",
              shortName = "log",
              doc = "Set the logging location",
              required = false)
    protected String toFile = null;

    /** do we want to silence the command line output */
    @Argument(fullName = "quiet_output_mode",
              shortName = "quiet",
              doc = "Set the logging to quiet mode, no output to stdout",
              required = false)
    protected Boolean quietMode = false;

    /** do we want to generate debugging information with the logs */
    @Argument(fullName = "debug_mode",
              shortName = "debug",
              doc = "Set the logging file string to include a lot of debugging information (SLOW!)",
              required = false)
    protected Boolean debugMode = false;

    /** this is used to indicate if they've asked for help */
    @Argument(fullName = "help", shortName = "h", doc = "Generate this help message", required = false)
    public Boolean help = false;

    /** our logging output patterns */
    private static String patternString = "%-5p %d{HH:mm:ss,SSS} %C{1} - %m %n";
    private static String debugPatternString = "%n[level] %p%n[date]\t\t %d{dd MMM yyyy HH:mm:ss,SSS} %n[class]\t\t %C %n[location]\t %l %n[line number]\t %L %n[message]\t %m %n";

    static {
        /**
         * The very first thing that any Sting application does is forces the JVM locale into US English, so that we don't have
         * to think about number formatting issues.
         */
        forceJVMLocaleToUSEnglish();
        // setup a basic log configuration
        CommandLineUtils.configureConsoleLogging();
    }


    /**
     * Allows a given application to return a brief description of itself.
     *
     * @return An ApplicationDetails object describing the current application.  Should not be null.
     */
    protected ApplicationDetails getApplicationDetails() {
        return new ApplicationDetails(ApplicationDetails.createDefaultHeader(getClass()),
                                      Collections.<String>emptyList(),
                                      ApplicationDetails.createDefaultRunningInstructions(getClass()),
                                      null);
    }

    /**
     * Subclasses of CommandLinePrograms can provide their own types of command-line arguments.
     * @return A collection of type descriptors generating implementation-dependent placeholders.
     */
    protected Collection<ArgumentTypeDescriptor> getArgumentTypeDescriptors() {
        return Collections.emptyList();
    }

    /**
     * Will this application want to vary its argument list dynamically?
     * If so, parse the command-line options and then prompt the subclass to return
     * a list of argument providers.
     *
     * @return Whether the application should vary command-line arguments dynamically.
     */
    protected boolean canAddArgumentsDynamically() { return false; }

    /**
     * Provide a list of object to inspect, looking for additional command-line arguments.
     *
     * @return A list of objects to inspect.
     */
    protected Class[] getArgumentSources() {
        return new Class[]{};
    }

    /**
     * Name this argument source.  Provides the (full) class name as a default.
     *
     * @param source The argument source.
     *
     * @return a name for the argument source.
     */
    protected String getArgumentSourceName( Class source ) { return source.toString(); }

    /**
     * this is the function that the inheriting class can expect to have called
     * when all the argument processing is done
     *
     * @return the return code to exit the program with
     * @throws Exception when an exception occurs
     */
    protected abstract int execute() throws Exception;

    public static int result = -1;

    /**
     * This function is called to start processing the command line, and kick
     * off the execute message of the program.
     *
     * @param clp  the command line program to execute
     * @param args the command line arguments passed in
     * @throws Exception when an exception occurs
     */
    @SuppressWarnings("unchecked")
    public static void start(CommandLineProgram clp, String[] args) throws Exception {

        try {
            // setup our log layout
            PatternLayout layout = new PatternLayout();

            Logger logger = CommandLineUtils.getStingLogger();

            // now set the layout of all the loggers to our layout
            CommandLineUtils.setLayout(logger, layout);

            // Initialize the logger using the defaults.
            clp.setupLoggerLevel(layout);

            // setup the parser
            ParsingEngine parser = clp.parser = new ParsingEngine(clp);
            parser.addArgumentSource(clp.getClass());

            // process the args
            if (clp.canAddArgumentsDynamically()) {
                // if the command-line program can toss in extra args, fetch them and reparse the arguments.
                parser.parse(args);

                // Allow invalid and missing required arguments to pass this validation step.
                //   - InvalidArgument in case these arguments are specified by plugins.
                //   - MissingRequiredArgument in case the user requested help.  Handle that later, once we've
                //                             determined the full complement of arguments.
                parser.validate(EnumSet.of(ParsingEngine.ValidationType.MissingRequiredArgument,
                                           ParsingEngine.ValidationType.InvalidArgument));
                parser.loadArgumentsIntoObject(clp);

                // Initialize the logger using the loaded command line.
                clp.setupLoggerLevel(layout);

                Class[] argumentSources = clp.getArgumentSources();
                for (Class argumentSource : argumentSources)
                    parser.addArgumentSource(clp.getArgumentSourceName(argumentSource), argumentSource);
                parser.parse(args);

                if (isHelpPresent(parser))
                    printHelpAndExit(clp, parser);

                parser.validate();
            } else {
                parser.parse(args);

                if (isHelpPresent(parser))
                    printHelpAndExit(clp, parser);

                parser.validate();
                parser.loadArgumentsIntoObject(clp);

                // Initialize the logger using the loaded command line.
                clp.setupLoggerLevel(layout);
            }

            // if they set the mode to quiet
            if (clp.quietMode) {

                // the only appender we should have is stdout, the following meathod is
                // deprecated, but the standard remove all appenders doesn't seem to work
                // TODO: find the right function
                //Category root = Category.getRoot();
                //root.removeAllAppenders();
                //logger.removeAllAppenders();
            }

            // if they specify a log location, output our data there
            if (clp.toFile != null) {
                FileAppender appender;
                try {
                    appender = new FileAppender(layout, clp.toFile, false);
                    logger.addAppender(appender);
                } catch (IOException e) {
                    throw new RuntimeException("Unable to re-route log output to " + clp.toFile + " make sure the destination exists");
                }
            }

            // regardless of what happens next, generate the header information
            HelpFormatter.generateHeaderInformation(clp.getApplicationDetails(), args);

            // call the execute
            CommandLineProgram.result = clp.execute();
        }
        catch (ArgumentException e) {
            clp.parser.printHelp(clp.getApplicationDetails());
            // Rethrow the exception to exit with an error.
            throw e;
        }
    }

    /**
     * Find fields in the object obj that look like command-line arguments, and put command-line
     * arguments into them.
     *
     * @param obj Object to inspect for command line arguments.
     */
    public void loadArgumentsIntoObject(Object obj) {
        parser.loadArgumentsIntoObject(obj);
    }

    /**
     * this function checks the logger level passed in on the command line, taking the lowest
     * level that was provided.
     * @param layout Pattern layout to format based on the logger level.
     */
    @SuppressWarnings("unchecked")
    private void setupLoggerLevel(PatternLayout layout) {
        // if we're in debug mode, set the mode up
        if (debugMode) {
            layout.setConversionPattern(debugPatternString);
        } else {
            layout.setConversionPattern(patternString);
        }

        // set the default logger level
        Level par;
        if (logging_level.toUpperCase().equals("DEBUG")) {
            par = Level.DEBUG;
        } else if (logging_level.toUpperCase().equals("ERROR")) {
            par = Level.ERROR;
        } else if (logging_level.toUpperCase().equals("FATAL")) {
            par = Level.FATAL;
        } else if (logging_level.toUpperCase().equals("INFO")) {
            par = Level.INFO;
        } else if (logging_level.toUpperCase().equals("WARN")) {
            par = Level.WARN;
        } else if (logging_level.toUpperCase().equals("OFF")) {
            par = Level.OFF;
        } else {
            // we don't understand the logging level, let's get out of here
            throw new ArgumentException("Unable to match: " + logging_level + " to a logging level, make sure it's a valid level (INFO, DEBUG, ERROR, FATAL, OFF)");
        }

        Logger.getRootLogger().setLevel(par);
    }

    /**
     * a function used to indicate an error occurred in the command line tool
     */
    private static void printDocumentationReference() {
        errorPrintf("Visit our wiki for extensive documentation http://www.broadinstitute.org/gsa/wiki%n");
        errorPrintf("Visit our forum to view answers to commonly asked questions http://getsatisfaction.com/gsa%n");
    }


    /**
     * Do a cursory search for the given argument.
     *
     * @param parser Parser
     *
     * @return True if help is present; false otherwise.
     */
    private static boolean isHelpPresent(ParsingEngine parser) {
        return parser.isArgumentPresent("help");
    }

    /**
     * Print help and exit.
     *
     * @param clp    Instance of the command-line program.
     * @param parser True if help is present; false otherwise.
     */
    private static void printHelpAndExit(CommandLineProgram clp, ParsingEngine parser) {
        parser.printHelp(clp.getApplicationDetails());
        System.exit(0);
    }

    private static void errorPrintf(String format, Object... s) {
        String formatted = String.format(format, s);

        if ( formatted.trim().equals("") )
            System.err.println("##### ERROR");
        else {
            for ( String part : formatted.split("\n") ) {
                System.err.println("##### ERROR " + part);
            }
        }
    }


    /**
     * used to indicate an error occured
     *
     * @param msg the message
     * @param e   the error
     */
    public static void exitSystemWithError(String msg, final Exception e) {
        errorPrintf("------------------------------------------------------------------------------------------%n");
        errorPrintf("stack trace %n");
        e.printStackTrace();

        errorPrintf("------------------------------------------------------------------------------------------%n");
        errorPrintf("A GATK RUNTIME ERROR has occurred (version %s):%n", CommandLineGATK.getVersionNumber());
        errorPrintf("%n");
        errorPrintf("Please visit the wiki to see if this is a known problem%n");
        errorPrintf("If not, please post the error, with stack trace, to the GATK forum%n");
        printDocumentationReference();
        if ( msg == null ) // some exceptions don't have detailed messages
            msg = "Code exception (see stack trace for error itself)";
        errorPrintf("%n");
        errorPrintf("MESSAGE: %s%n", msg.trim());
        errorPrintf("------------------------------------------------------------------------------------------%n");
        System.exit(1);
    }

    public static void exitSystemWithUserError(final Exception e) {
        if ( e.getMessage() == null )
            throw new ReviewedStingException("UserException found with no message!", e);

        errorPrintf("------------------------------------------------------------------------------------------%n");
        errorPrintf("A USER ERROR has occurred (version %s): %n", CommandLineGATK.getVersionNumber());
        errorPrintf("The invalid arguments or inputs must be corrected before the GATK can proceed%n");
        errorPrintf("Please do not post this error to the GATK forum%n");
        errorPrintf("%n");
        errorPrintf("See the documentation (rerun with -h) for this tool to view allowable command-line arguments.%n");
        printDocumentationReference();
        errorPrintf("%n");
        errorPrintf("MESSAGE: %s%n", e.getMessage().trim());
        errorPrintf("------------------------------------------------------------------------------------------%n");
        System.exit(1);
    }


    /**
     * used to indicate an error occured
     *
     * @param e the exception occured
     */
    public static void exitSystemWithError(Exception e) {
        exitSystemWithError(e.getMessage(), e);
    }

    /**
     * A hack to ensure that numbers are always formatted in the US style.
     */
    protected static void forceJVMLocaleToUSEnglish() {
        Locale.setDefault(Locale.US);
    }
}
