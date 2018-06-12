Feature: Applicant changes their email

  Scenario: An eligible applicant chooses to use their GG email for hts
    Given the user has logged in and passed IV
    When they confirm their details and continue to create an account
    And they select their GG email and proceed
    Then they see the final Create Account page

  @zap
  Scenario: An eligible applicant chooses to give a new email address for hts
    Given the user has logged in and passed IV
    When they start to create an account
    And they enter a new email address
    Then they see the verify your email page
#///////////////////////////////////////////

  Scenario: An eligible applicant updates their email address
    Given an applicant has NOT already had their new email address verified
    #Generate random email address and save in a variable
    And they submit their new email address for Help to Save
    #Include underlying code in four steps from original scenario
    When they click on the email verification link
    #Take the email address stored in the variable, create the token (with email address, continue URL, base64 encoding, etc.), add token to full URL (base URL + /email-verification/verify?token=<token>) and store URL in variable
    Then they see that their email address has been successfully verified
    #In Selenium navigate to URL stored in variable

#/////////////////////////////////////////
  Scenario: An eligible applicant wants to give an email address
    Given HMRC doesn't currently hold an email address for the user
    When they start to create an account
    Then they are asked to enter an email address

  @HTS-400
  Scenario: Applicant requests a re-send of the verification email
    Given they've chosen to enter a new email address during the application process
    When they request a re-send of the verification email
    Then they are asked to check their email for a verification email

  @HTS-399
  Scenario: Applicant changes their mind about the new email they provided
    Given they've chosen to enter a new email address during the application process
    When they want to change their email again
    Then they are asked to check their email for a verification email
